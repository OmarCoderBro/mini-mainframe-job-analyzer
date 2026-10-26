import scala.io.{BufferedSource, Source}
import scala.util.Try

/**
 * JobReport — reads Java-generated output files and produces a terminal
 * operational report, mirroring how z/OS operational staff review batch
 * job completion status and return codes after a production run.
 */
object JobReport {

  // ── data model ──────────────────────────────────────────────────────────────
  case class StepRecord(
    jobId:      String,
    step:       String,
    rc:         Int,
    durationMs: Long,
    status:     String
  )

  def main(args: Array[String]): Unit = {
    val outputDir = if (args.nonEmpty) args(0) else "output"

    // ── load metrics written by Java processor ────────────────────────────────
    val metrics = loadProperties(s"$outputDir/metrics.properties")

    val jobName      = metrics.getOrElse("job_name",               "UNKNOWN")
    val totalRecs    = metrics.getOrElse("total_records",          "0").toInt
    val validRecs    = metrics.getOrElse("valid_records",          "0").toInt
    val rejectedRecs = metrics.getOrElse("rejected_records",       "0").toInt
    val durationMs   = metrics.getOrElse("processing_duration_ms", "0").toLong
    val throughput   = metrics.getOrElse("throughput_rps",         "0").toLong

    // ── load processed records ────────────────────────────────────────────────
    val records: Seq[StepRecord] = loadProcessedRecords(s"$outputDir/processed.csv")

    // ── group steps by job_id ─────────────────────────────────────────────────
    val byJob: Map[String, Seq[StepRecord]] = records.groupBy(_.jobId)
    val uniqueJobs = byJob.size

    // ── classify each job ─────────────────────────────────────────────────────
    // FAILED     : any step with status FAILURE  (RC >= 8)
    // UNKNOWN    : no FAILURE step, but at least one UNKNOWN step
    // SUCCESSFUL : all steps SUCCESS or WARNING   (RC 0 or 4)
    val jobStatuses: Map[String, String] = byJob.map { case (id, steps) =>
      val statuses = steps.map(_.status).toSet
      val label =
        if (statuses.contains("FAILURE")) "FAILED"
        else if (statuses.contains("UNKNOWN")) "UNKNOWN"
        else "SUCCESSFUL"
      id -> label
    }

    val successfulJobs = jobStatuses.values.count(_ == "SUCCESSFUL")
    val failedJobs     = jobStatuses.values.count(_ == "FAILED")
    val unknownJobs    = jobStatuses.values.count(_ == "UNKNOWN")

    // ── aggregate step-level metrics ──────────────────────────────────────────
    val avgDuration: Double =
      if (records.isEmpty) 0.0
      else records.map(_.durationMs).sum.toDouble / records.size

    val mostCommonFailureStep: String = {
      val failSteps = records.filter(_.status == "FAILURE").map(_.step)
      if (failSteps.isEmpty) "N/A"
      else failSteps.groupBy(identity).maxBy(_._2.size)._1
    }

    val highestRC: Int =
      if (records.isEmpty) 0 else records.map(_.rc).max

    // ── print report ──────────────────────────────────────────────────────────
    val bar = "=" * 50

    println(bar)
    println("      MAINFRAME JOB ANALYSIS REPORT")
    println(bar)
    println()
    println(s"Analysis Job : $jobName")
    println( "Status       : COMPLETED")
    println()
    println("── Input Records ────────────────────────────────")
    println(f"Total Records    : $totalRecs%,d")
    println(f"Valid Records    : $validRecs%,d")
    println(f"Rejected Records : $rejectedRecs%,d")
    println()
    println("── Job Summary ──────────────────────────────────")
    println(f"Unique Jobs      : $uniqueJobs%,d")
    println(f"Successful Jobs  : $successfulJobs%,d")
    println(f"Failed Jobs      : $failedJobs%,d")
    println(f"Unknown Jobs     : $unknownJobs%,d")
    println()
    println("── Step Analytics ───────────────────────────────")
    println(f"Avg Step Duration        : $avgDuration%.0f ms")
    println(s"Most Common Failure Step : $mostCommonFailureStep")
    println(s"Highest Return Code      : $highestRC")
    println()
    println("── Analyzer Performance ─────────────────────────")
    println(s"Processing Time  : $durationMs ms")
    println(s"Throughput       : ${"%,d".format(throughput)} records/sec")
    println()
    println(bar)
  }

  private def withSource[A](path: String)(f: BufferedSource => A): Option[A] =
    Try(Source.fromFile(path)).toOption.map { src =>
      try f(src) finally src.close()
    }

  def loadProcessedRecords(path: String): Seq[StepRecord] =
    withSource(path) { src =>
      src.getLines()
        .drop(1) // skip header: job_id,job_name,step,return_code,duration_ms,status
        .flatMap { line =>
          val f = line.split(",", -1)
          if (f.length >= 6) {
            Try(StepRecord(
              jobId      = f(0).trim,
              step       = f(2).trim,
              rc         = f(3).trim.toInt,
              durationMs = f(4).trim.toLong,
              status     = f(5).trim
            )).toOption
          } else None
        }
        .toSeq
    }.getOrElse {
      System.err.println(s"[ERROR] Cannot read $path")
      Seq.empty
    }

  def loadProperties(path: String): Map[String, String] =
    withSource(path) { src =>
      src.getLines()
        .filter(_.contains("="))
        .map { line =>
          val idx = line.indexOf('=')
          line.substring(0, idx).trim -> line.substring(idx + 1).trim
        }
        .toMap
    }.getOrElse(Map.empty)
}
