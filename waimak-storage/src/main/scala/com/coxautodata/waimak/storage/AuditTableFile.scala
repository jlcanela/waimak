package com.coxautodata.waimak.storage

import java.sql.Timestamp
import java.time.Duration

import com.coxautodata.waimak.log.Logging
import com.coxautodata.waimak.storage.AuditTable.CompactionPartitioner
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.spark.sql._
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._

import scala.util.{Failure, Success, Try}

/**
  * Implementation of the [[AuditTable]] which is backed up by append only block storage like HDFS.
  *
  * Created by Alexei Perelighin on 2018/03/03
  *
  * @param tableInfo   static details about the table, with custom metadata
  * @param regions     list of region details
  * @param storageOps  object that actually interacts with the physical storage
  * @param baseFolder  parent folder which contains folders with table names
  * @param newRegionID function that generates region ids
  */
class AuditTableFile(val tableInfo: AuditTableInfo
                     , override val regions: Seq[AuditTableRegionInfo]
                     , val storageOps: FileStorageOps
                     , val baseFolder: Path
                     , val newRegionID: (AuditTableFile) => String
                    ) extends AuditTable with Logging {

  import AuditTableFile._

  /**
    * Not thread safe. Protection against using mutator functions more than one time.
    */
  protected var wasModified = false

  protected[storage] val tablePath = new Path(baseFolder, tableInfo.table_name)

  protected[storage] val regionInfoBasePath = new Path(baseFolder, AuditTableFile.REGION_INFO_DIRECTORY)

  protected val coldPath = new Path(tablePath, s"$STORE_TYPE_COLUMN=$COLD_PARTITION")

  protected val hotPath = new Path(tablePath, s"$STORE_TYPE_COLUMN=$HOT_PARTITION")

  protected val metaBasePath = new Path(tablePath, "_metadata")

  override def meta: Map[String, String] = tableInfo.meta

  override def tableName: String = tableInfo.table_name

  override def getLatestTimestamp(): Option[Timestamp] = if (regions.nonEmpty) Some(regions.map(_.max_last_updated).reduce((t1, t2) => if (t1.after(t2)) t1 else t2)) else None

  override def append(ds: Dataset[_], lastUpdated: Column, appendTS: Timestamp): Try[(AuditTable, Long)] = {
    val res: Try[(AuditTableFile.this.type, Long)] = Try {
      markToUpdate()
      val regionID = newRegionID(this)
      val regionPath = new Path(hotPath, s"$STORE_REGION_COLUMN=" + regionID)
      logInfo(s"Creating region in path [${regionPath.toString}]")
      val withLastUpdated = ds.withColumn(DE_LAST_UPDATED_COLUMN, lastUpdated)
      storageOps.writeParquet(tableInfo.table_name, regionPath, withLastUpdated)
      val (count, max_latest_ts) = calcRegionStats(storageOps.openParquet(regionPath).get)
      val region = AuditTableRegionInfo(tableInfo.table_name, HOT_PARTITION, regionID, appendTS, false, count, max_latest_ts)
      logInfo(s"Created region $region")
      (setRegions(this, regions :+ region, appendedRegions = Some(Seq(region))).asInstanceOf[this.type], count)
    }
    res
  }

  override def snapshot(ts: Timestamp): Option[Dataset[_]] = {
    //TODO: optimise and explore other solutions with the use of counts, as smaller counts should avoid shuffle, hot applied to cold
    allBetween(None, Some(ts)).map(deduplicate)
      .map(_.drop(DE_LAST_UPDATED_COLUMN))
  }

  def deduplicate(ds: Dataset[_]): Dataset[_] = {
    val primaryKeyColumns = tableInfo.primary_keys.map(ds(_))
    val windowLatest = Window.partitionBy(primaryKeyColumns: _*).orderBy(ds(DE_LAST_UPDATED_COLUMN).desc)
    ds.withColumn("_rowNum", row_number().over(windowLatest)).filter("_rowNum = 1").drop("_rowNum")
  }

  /**
    * Request optimisation of the storage layer.
    *
    * Fails when is called second time on same instance.
    *
    * @param compactTS               timestamp of when the compaction is requested, will not be used for any filtering of the data
    * @param trashMaxAge             Maximum age of old region files kept in the .Trash folder
    *                                after a compaction has happened.
    * @param smallRegionRowThreshold the row number threshold to use for determining small regions to be compacted.
    *                                Default is 50000000
    * @param compactionPartitioner   a partitioner object that dictates how many partitions should be generated
    *                                for a given region
    * @param recompactAll            Whether to recompact all regions regardless of size (i.e. ignore smallRegionRowThreshold)
    * @return new state of the AuditTable
    */
  override def compact(compactTS: Timestamp
                       , trashMaxAge: Duration
                       , smallRegionRowThreshold: Long
                       , compactionPartitioner: CompactionPartitioner
                       , recompactAll: Boolean): Try[AuditTable] = {
    val res: Try[AuditTableFile] = Try(
      markToUpdate())
      .flatMap {
        _ =>
          compactRegions(regionsToCompact(smallRegionRowThreshold, recompactAll), compactTS, compactionPartitioner)
      }
      .map { f =>
        f.storageOps.purgeTrash(f.tableName, compactTS, trashMaxAge)
        f
      }
    res.map(_.asInstanceOf[this.type])
  }

  override def initNewTable(): Try[this.type] = {
    logInfo(s"Initialising table [${tableInfo.table_name}] in path [${tablePath.toString}]")
    val res: Try[this.type] = Try(true)
      .flatMap(_ => if (storageOps.pathExists(tablePath)) Failure(StorageException(s"Table [${tableInfo.table_name}] already exists in path [${tablePath.toString}]")) else Success(true))
      .flatMap(_ => if (tableInfo.primary_keys.isEmpty) Failure(StorageException(s"Table [${tableInfo.table_name}] must have at least one column in primary keys.")) else Success(true))
      .flatMap(_ => if (storageOps.mkdirs(hotPath)) Success(true) else Failure(StorageException(s"Table [${tableInfo.table_name}] can not be initialised, can not create folder ${hotPath.toString}")))
      .flatMap(_ => if (storageOps.mkdirs(coldPath)) Success(true) else Failure(StorageException(s"Table [${tableInfo.table_name}] can not be initialised, can not create folder ${coldPath.toString}")))
      .flatMap(_ => storageOps.writeAuditTableInfo(baseFolder, tableInfo))
      .map(_ => setRegions(this, Seq.empty, appendedRegions = None).asInstanceOf[this.type])
    res
  }

  override def updateTableInfo(tableInfo: AuditTableInfo): Try[AuditTable] =
    storageOps.writeAuditTableInfo(baseFolder, tableInfo)
      .map(info => new AuditTableFile(info, regions, storageOps, baseFolder, newRegionID))

  override def allBetween(from: Option[Timestamp], to: Option[Timestamp]): Option[Dataset[_]] = {
    val regionIDs = activeRegionIDs()
    regionIDs.flatMap { ids =>
      val df = storageOps.openParquet(tablePath)
      df.map { rows =>
        val auditRows = rows.filter(rows(STORE_TYPE_COLUMN).isin(HOT_PARTITION, COLD_PARTITION) && rows(STORE_REGION_COLUMN).isin(ids: _*))
        auditRows.filter(auditRows(DE_LAST_UPDATED_COLUMN).between(from.getOrElse(lowTimestamp), to.getOrElse(highTimestamp)))
      }
    }
  }

  /**
    * Returns regions ids for all active regions.
    *
    * @return
    */
  def activeRegionIDs(): Option[Seq[String]] = if (regions.isEmpty) None else Some(regions.filter(!_.is_deprecated).map(_.store_region))

  protected def regionsToCompact(smallRegionRowThreshold: Long, recompactAll: Boolean): Seq[AuditTableRegionInfo] = {

    def coldNeedsCompacting(r: AuditTableRegionInfo): Boolean = r.store_type == COLD_PARTITION && r.count < smallRegionRowThreshold

    def hotNeedsCompacting(r: AuditTableRegionInfo): Boolean = !r.is_deprecated && r.store_type == HOT_PARTITION

    if (recompactAll || !tableInfo.retain_history) regions
    else {
      val r = regions.filter(r => coldNeedsCompacting(r) || hotNeedsCompacting(r))
      //Don't recompact if we have a single cold region
      if (r.length < 2 && !r.exists(_.store_type == HOT_PARTITION)) Seq.empty
      else r
    }

  }

  protected def compactRegions(toCompact: Seq[AuditTableRegionInfo]
                               , compactTS: Timestamp
                               , compactionPartitioner: CompactionPartitioner): Try[AuditTableFile] = {
    Try {
      val res = if (toCompact.isEmpty) new AuditTableFile(this.tableInfo, this.regions, this.storageOps, this.baseFolder, this.newRegionID)
      else {
        val ids = toCompact.map(_.store_region)
        val regionID = newRegionID(this)
        val newRegionPath = new Path(coldPath, s"$STORE_REGION_COLUMN=" + regionID)
        logInfo(s"Compacting regions ${ids.mkString("[", ", ", "]")} in path [${newRegionPath.toString}]")
        if (storageOps.pathExists(newRegionPath)) throw StorageException(s"Can not compact table [$tableName], as path [${newRegionPath.toString}] already exists")

        val regionPaths = toCompact.map {
          case h if h.store_type == HOT_PARTITION => new Path(hotPath, s"$STORE_REGION_COLUMN=${h.store_region}")
          case c if c.store_type == COLD_PARTITION => new Path(coldPath, s"$STORE_REGION_COLUMN=${c.store_region}")
          case u => throw StorageException(s"Unknown store type ${u.store_type} for region ${u.store_type}")
        }

        val data = storageOps.openParquet(regionPaths.head, regionPaths.tail: _*).map {
          case ds if !tableInfo.retain_history => deduplicate(ds)
          case ds => ds
        }

        val newRegionSet = data.map { rows =>
          //Clear current region info to prevent corruption on failure
          clearTableRegionCache(this)
          val currentNumPartitions = rows.rdd.getNumPartitions
          val newNumPartitions = compactionPartitioner(rows, toCompact.map(_.count).sum)
          val rowsToCompactRepartitioned = if (newNumPartitions > currentNumPartitions) {
            rows.repartition(newNumPartitions)
          } else rows.coalesce(newNumPartitions)
          storageOps.atomicWriteAndCleanup(tableInfo.table_name, rowsToCompactRepartitioned, newRegionPath, regionPaths, compactTS)
          val (count, max_latest_ts) = calcRegionStats(storageOps.openParquet(newRegionPath).get)
          val idSet = ids.toSet
          val remainingRegions = regions.filter(r => !idSet.contains(r.store_region))
          val region = AuditTableRegionInfo(tableInfo.table_name, COLD_PARTITION, regionID, compactTS, false, count, max_latest_ts)
          logInfo(s"Compacted region ${region.toString} was created.")
          remainingRegions :+ region
        }
        newRegionSet.fold(this)(r => setRegions(this, r, appendedRegions = None))
      }
      res
    }
  }

  protected def calcRegionStats(ds: Dataset[_]): (Long, Timestamp) = {
    ds.select(count(ds(DE_LAST_UPDATED_COLUMN)), max(ds(DE_LAST_UPDATED_COLUMN)))
      .collect().map(r => (r.getAs[Long](0), Option(r.getAs[Timestamp](1)).getOrElse(lowTimestamp)))
      .head
  }

  /**
    * Each function that modifies the state of the storage layer must call this function in the first line. As
    * audit's table state can be modified only once.
    */
  protected def markToUpdate(): Unit = if (wasModified) throw StorageException(s"Table [$tableName] can no longer be updated.") else wasModified = true

}

object AuditTableFile extends Logging {

  val STORE_TYPE_COLUMN = "de_store_type"

  val STORE_REGION_COLUMN = "de_store_region"

  val DE_LAST_UPDATED_COLUMN = "_de_last_updated"

  val HOT_PARTITION = "hot"

  val COLD_PARTITION = "cold"

  val REGION_INFO_DIRECTORY = ".regioninfo"

  val lowTimestamp: Timestamp = Timestamp.valueOf("0001-01-01 00:00:00")

  val highTimestamp: Timestamp = Timestamp.valueOf("9999-12-31 23:59:59")

  /**
    * Generic function that generates sequential region ids that are padded on the left with zeros up to 20 chars.
    *
    * @param table
    * @return
    */
  def nextLongRegion(table: AuditTableFile): String = f"r${table.activeRegionIDs().map(_.max.drop(1).toLong + 1).getOrElse(0L)}%020d"

  /**
    * Creates a copy of the table with new list of regions.
    *
    * @param audit           - Audit table with old regions
    * @param allRegions      - Complete set of current regions
    * @param appendedRegions - Optional list of regions that have been appended. If given, only new regions are written
    *                        to cache. If None the current cached region information is completely rewritten.
    *                        If the case of any region deletes, this should be None.
    * @return
    */
  def setRegions(audit: AuditTableFile, allRegions: Seq[AuditTableRegionInfo], appendedRegions: Option[Seq[AuditTableRegionInfo]]): AuditTableFile = {
    val spark = audit.storageOps.sparkSession
    import spark.implicits._
    val regionsToWrite = appendedRegions.getOrElse(allRegions)
    val regionInfoDS = audit.storageOps.sparkSession.createDataset(regionsToWrite).coalesce(1)
    audit.storageOps.writeParquet(
      audit.tableName,
      new Path(audit.regionInfoBasePath, audit.tableName),
      regionInfoDS,
      overwrite = appendedRegions.isEmpty,
      tempSubfolder = Some(REGION_INFO_DIRECTORY))
    new AuditTableFile(audit.tableInfo, allRegions, audit.storageOps, audit.baseFolder, audit.newRegionID)
  }

  def clearTableRegionCache(audit: AuditTableFile): Unit = {
    val tableRegionInfoPath = new Path(audit.regionInfoBasePath, audit.tableName)
    Try(audit.storageOps.deletePath(tableRegionInfoPath, recursive = true))
      .recover { case e => throw StorageException(s"Failed to delete region information for table [${audit.tableName}]", e) }
      .get
  }

  /**
    * In one spark job scans all of the specified tables and infers stats about each region of the listed tables.
    *
    * @param sparkSession
    * @param fileStorage
    * @param basePath
    * @param tableNames
    * @param includeHot if true, than hot regions will be included in the scan. By default is true. False is useful
    *                   when reading production data from dev environments, as compactions will be happening in out
    *                   of office hours, this helps to avoid reading data in an inconsistent state.
    * @return
    */
  def inferRegionsWithStats(sparkSession: SparkSession, fileStorage: FileStorageOps, basePath: Path, tableNames: Seq[String], includeHot: Boolean = true, skipRegionInfoCache: Boolean = false): Seq[AuditTableRegionInfo] = {

    // Get all region info from cache and paths
    val allCacheInfo = {
      if (skipRegionInfoCache)
        Map.empty[(String, String, String), AuditTableRegionInfo]
      else
        inferRegionsFromCache(fileStorage, basePath, tableNames, includeHot).map(t => (t.table_name, t.store_type, t.store_region) -> t).toMap
    }
    val allPathInfo = inferRegionsFromPaths(fileStorage, basePath, tableNames, includeHot).map(t => (t.table_name, t.store_type, t.store_region) -> t).toMap

    // Keep only cached info that matches files as the region info might be invalid
    val validCacheInfo = calculateValidCacheInfo(allCacheInfo, allPathInfo)
    val validCachedTables = validCacheInfo.map(_.table_name).toSet

    //Only infer regions using paths and parquet from tables that are not in the cache or the cache looks invalid
    val tablesMissingFromCache = (tableNames.toSet diff validCachedTables).toSeq
    val fromPaths = allPathInfo.filterKeys(k => tablesMissingFromCache.contains(k._1))
    val fromParquet = inferRegionsFromParquet(sparkSession, fileStorage, basePath, tablesMissingFromCache, includeHot).map(t => (t.table_name, t.store_type, t.store_region) -> t).toMap

    //Merge regions, take preference for fromParquet, then combine with cache-backed region info
    (fromPaths.keySet ++ fromParquet.keySet).toSeq.map(k => fromParquet.getOrElse(k, fromPaths(k))) ++ validCacheInfo
  }

  type RegionMap = Map[(String, String, String), AuditTableRegionInfo]

  private[storage] def calculateValidCacheInfo(allCacheInfo: RegionMap, allPathInfo: RegionMap): Seq[AuditTableRegionInfo] = {
    val cacheInfoByTable = allCacheInfo.keySet.groupBy(_._1)
    val pathInfoByTable = allPathInfo.keySet.groupBy(_._1)
    val validCachedTables = cacheInfoByTable.filter { case (t, r) => pathInfoByTable.get(t).contains(r) }.keySet
    val validCacheInfo = allCacheInfo.filterKeys(t => validCachedTables.contains(t._1)).values.toSeq
    (cacheInfoByTable.keySet diff validCachedTables)
      .reduceLeftOption((z, t) => s"$z, $t")
      .foreach(
        t => logWarning(
          s"The cached region information for the following tables looks invalid, it does not match the found partition folders. " +
            s"The cached region information for these tables will be ignored, this will affect performance: [$t]"
        )
      )
    validCacheInfo
  }

  private[storage] def inferRegionsFromCache(fileStorage: FileStorageOps, basePath: Path, tableNames: Seq[String], includeHot: Boolean): Seq[AuditTableRegionInfo] = {

    val parFun: PartialFunction[FileStatus, Path] = {
      case r if r.isDirectory => r.getPath
    }

    val presentTables = fileStorage.globTablePaths(new Path(basePath, REGION_INFO_DIRECTORY), tableNames, Seq.empty, parFun).toList
    presentTables match {
      case h :: tail =>
        // Read cache, filter if needed and collect
        val spark = fileStorage.sparkSession
        import spark.implicits._
        val cache = fileStorage.openParquet(h, tail: _*)
          .map(
            _.as[AuditTableRegionInfo]
              .filter(includeHot || _.store_type != HOT_PARTITION)
              .collect()
              .toSeq
          )
        cache.getOrElse {
          logWarning(s"Unable to read region cache info for tables: [${presentTables.map(_.getName).mkString(", ")}]. " +
            s"Defaulting to reading from file and/or parquet; this will affect performance.")
          Seq.empty[AuditTableRegionInfo]
        }
      case Nil => Seq.empty
    }

  }

  /**
    * Infer all regions using information found in Parquet files only. This will not include regions that have no data.
    * Use [[inferRegionsFromPaths]] to find information about regions with empty Parquet files.
    */
  private def inferRegionsFromParquet(sparkSession: SparkSession, fileStorage: FileStorageOps, basePath: Path, tableNames: Seq[String], includeHot: Boolean)
  : Seq[AuditTableRegionInfo] = {
    val spark = sparkSession
    import spark.implicits._

    val regions = tableNames.iterator.grouped(20).flatMap { page =>
      val pageDFs: Seq[DataFrame] = page.map { table =>
        val data = fileStorage.openParquet(new Path(basePath, table))
        data.map(df => if (includeHot) df.filter(df(STORE_TYPE_COLUMN).isin(HOT_PARTITION, COLD_PARTITION)) else df.filter(df(STORE_TYPE_COLUMN).isin(COLD_PARTITION)))
          .map { df =>
            df.groupBy(df(STORE_TYPE_COLUMN), df(STORE_REGION_COLUMN))
              .agg(count(df(DE_LAST_UPDATED_COLUMN)).as("count"), max(df(DE_LAST_UPDATED_COLUMN)).as("max_last_updated"))
              .select(
                lit(table).as("table_name")
                , df(STORE_TYPE_COLUMN).as("store_type")
                , df(STORE_REGION_COLUMN).as("store_region")
                // TODO: can we actually carry this in the data?
                , lit(lowTimestamp).as("created_on")
                , lit(false).as("is_deprecated")
                , $"count"
                , $"max_last_updated"
              )
          }
      }.filter(_.isDefined).map(_.get)
      if (pageDFs.isEmpty) Seq.empty
      else pageDFs.reduce(_ union _).as[AuditTableRegionInfo].collect()
    }.toSeq
    regions
  }

  /**
    * Infer all regions using information found in paths only. This will not include any specific information about region details.
    * Counts will be 0, and all timestamps will be [[lowTimestamp]]. This information should be augmented with details from [[inferRegionsFromParquet]].
    */
  private[storage] def inferRegionsFromPaths(fileStorage: FileStorageOps, basePath: Path, tableNames: Seq[String], includeHot: Boolean): Seq[AuditTableRegionInfo] = {

    val parFun: PartialFunction[FileStatus, AuditTableRegionInfo] = {
      case r if r.isDirectory => val path = r.getPath
        AuditTableRegionInfo(
          path.getParent.getParent.getName,
          path.getParent.getName.split('=')(1),
          path.getName.split('=')(1),
          lowTimestamp,
          false,
          0,
          lowTimestamp
        )
    }

    fileStorage.globTablePaths(basePath, tableNames, Seq(s"$STORE_TYPE_COLUMN=${if (includeHot) "*" else COLD_PARTITION}", s"$STORE_REGION_COLUMN=*"), parFun)

  }

  /**
    * Reads the state of the multiple Audit Tables. It will scan the state of regions of all specified tables in one go.
    *
    * @param sparkSession
    * @param fileStorage object that actually interacts with the physical storage
    * @param basePath    parent folder which contains folders with table names
    * @param newRegionID function that generates region ids
    * @param tableNames  list of tables to open
    * @param includeHot  include hot regions in the table
    * @return (Map[TABLE NAME, AuditTable], Seq[MISSING TABLES]) - audit table objects that exist and
    *         of table names that were not found under the basePath
    */
  def openTables(sparkSession: SparkSession, fileStorage: FileStorageOps, basePath: Path
                 , tableNames: Seq[String], includeHot: Boolean = true)(newRegionID: (AuditTableFile) => String): (Map[String, Try[AuditTableFile]], Seq[String]) = {
    val existingTables = fileStorage.listTables(basePath).toSet
    val (exist, not) = tableNames.partition(existingTables.contains)
    val tableRegions = inferRegionsWithStats(sparkSession, fileStorage, basePath, exist, includeHot).groupBy(_.table_name)
    // some might not have regions yet
    val tablesObjs = exist.foldLeft(Map.empty[String, Try[AuditTableFile]]) { (res, tableName) =>
      val info = fileStorage.readAuditTableInfo(basePath, tableName)
      val regions = tableRegions.getOrElse(tableName, Seq.empty[AuditTableRegionInfo])
      res + (tableName -> info.map(i => new AuditTableFile(i, regions, fileStorage, basePath, newRegionID)))
    }
    (tablesObjs, not)
  }

  /**
    * Creates a table in the physical storage layer.
    *
    * @param sparkSession
    * @param fileStorage
    * @param basePath
    * @param tableInfo table metadata
    * @param newRegionID
    * @return
    */
  def createTable(sparkSession: SparkSession, fileStorage: FileStorageOps, basePath: Path
                  , tableInfo: AuditTableInfo)(newRegionID: (AuditTableFile) => String): Try[AuditTableFile] = {
    val table = new AuditTableFile(tableInfo, Seq.empty, fileStorage, basePath, newRegionID)
    table.initNewTable()
  }
}

/**
  * Static information about the table, that is persisted when audit table is initialised.
  *
  * @param table_name     name of the table
  * @param primary_keys   list of columns that make up primary key, these will be used for snapshot generation and
  *                       record deduplication
  * @param retain_history whether to retain history for this table. If set to false, the table will be deduplicated
  *                       on every compaction
  * @param meta           application/custom metadata that will not be used in this library.
  */
case class AuditTableInfo(table_name: String, primary_keys: Seq[String], meta: Map[String, String], retain_history: Boolean)

/**
  *
  * @param table_name       name of the table
  * @param store_type       cold or hot, appended regions are added to hot and after compaction make it into cold. Cold
  *                         regions can also be compacted
  * @param store_region     id of the region, for simplicity, at least for now it will be GUID
  * @param created_on       timestamp when region was created as a result of an append or compact operation
  * @param is_deprecated    true - its data was compacted into another region, false - it was not compacted
  * @param count            number of records in the region, can be used for optimisation and compaction decisions
  * @param max_last_updated all records in the audit table will contain column that shows the last updated time,
  *                         this will be used to generated ingestion queries
  */
case class AuditTableRegionInfo(table_name: String, store_type: String, store_region: String, created_on: Timestamp
                                , is_deprecated: Boolean, count: Long, max_last_updated: Timestamp)
