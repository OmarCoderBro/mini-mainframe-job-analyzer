import java.io.*;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * JobLogProcessor — reads mainframe-style job execution records from a CSV,
 * validates and classifies each step, writes processed/error output files,
 * tracks the analysis job lifecycle, and records performance metrics.
 *
 * Inspired by z/OS batch job processing concepts (JCL, JES, step return codes).
 */
public class JobLogProcessor {

    // Return-code classification thresholds (simplified z/OS policy)
    private static final int RC_SUCCESS  = 0;
    private static final int RC_WARNING  = 4;
    private static final int RC_FAILURE  = 8;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── lifecycle state ──────────────────────────────────────────────────────
    private static PrintWriter jobLog;
    private static String jobName = "LOG_ANALYSIS";

    public static void main(String[] args) throws Exception {

        // ── read job.conf (JCL-inspired configuration) ─────────────────────
        String inputPath  = "data/job_logs.csv";
        String outputDir  = "output";

        File conf = new File("job.conf");
        if (conf.exists()) {
            for (String line : Files.readAllLines(conf.toPath())) {
                line = line.trim();
                if (line.startsWith("JOB_NAME="))  jobName   = line.substring(9);
                if (line.startsWith("INPUT="))      inputPath = line.substring(6);
                if (line.startsWith("OUTPUT="))     outputDir = line.substring(7).replaceAll("/$", "");
            }
        }

        // ── prepare output directory ─────────────────────────────────────────
        Files.createDirectories(Paths.get(outputDir));

        // ── open job log (append=false → fresh run each time) ───────────────
        jobLog = new PrintWriter(new FileWriter(outputDir + "/job.log", false));

        jobStatus("STARTED", jobName);

        // ── open input file (job-level failure if missing) ───────────────────
        File inputFile = new File(inputPath);
        if (!inputFile.exists() || !inputFile.canRead()) {
            jobStatus("FAILED", jobName + " — input file not found: " + inputPath);
            jobLog.close();
            System.exit(1);
        }

        log("Processing " + inputPath);

        // ── open output writers ──────────────────────────────────────────────
        PrintWriter processed;
        PrintWriter errors;
        PrintWriter metrics;
        try {
            processed = new PrintWriter(new FileWriter(outputDir + "/processed.csv", false));
            errors    = new PrintWriter(new FileWriter(outputDir + "/errors.csv",    false));
            metrics   = new PrintWriter(new FileWriter(outputDir + "/metrics.properties", false));
        } catch (IOException e) {
            jobStatus("FAILED", jobName + " — cannot write output: " + e.getMessage());
            jobLog.close();
            System.exit(1);
            return;
        }

        processed.println("job_id,job_name,step,return_code,duration_ms,status");
        errors.println("line_number,raw_record,rejection_reason");

        long startNs = System.nanoTime();

        int totalRecords = 0;
        int validCount   = 0;
        int rejectedCount = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String header = reader.readLine(); // skip header
            if (header == null) {
                jobStatus("FAILED", jobName + " — input file is empty");
                processed.close(); errors.close(); metrics.close(); jobLog.close();
                System.exit(1);
            }

            String line;
            int lineNumber = 1; // 1 = header, data starts at 2
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;
                totalRecords++;

                String rejection = validate(line);
                if (rejection != null) {
                    rejectedCount++;
                    // escape raw record for CSV embedding
                    String safe = line.replace("\"", "\"\"");
                    errors.println(lineNumber + ",\"" + safe + "\",\"" + rejection + "\"");
                } else {
                    String[] f = line.split(",", -1);
                    String jobId    = f[0].trim();
                    String jobNm    = f[1].trim();
                    String step     = f[2].trim();
                    int    rc       = Integer.parseInt(f[3].trim());
                    long   duration = Long.parseLong(f[4].trim());
                    String status   = classify(rc);

                    processed.println(jobId + "," + jobNm + "," + step + ","
                            + rc + "," + duration + "," + status);
                    validCount++;
                }
            }
        } catch (IOException e) {
            jobStatus("FAILED", jobName + " — read error: " + e.getMessage());
            processed.close(); errors.close(); metrics.close(); jobLog.close();
            System.exit(1);
        }

        long durationNs = System.nanoTime() - startNs;
        long durationMs = durationNs / 1_000_000;
        double throughput = totalRecords == 0 ? 0.0
                : (totalRecords * 1_000_000_000.0) / durationNs;

        processed.close();
        errors.close();

        // ── write metrics ────────────────────────────────────────────────────
        metrics.println("job_name=" + jobName);
        metrics.println("total_records=" + totalRecords);
        metrics.println("valid_records=" + validCount);
        metrics.println("rejected_records=" + rejectedCount);
        metrics.println("processing_duration_ms=" + durationMs);
        metrics.printf("throughput_rps=%.0f%n", throughput);
        metrics.close();

        log(String.format("Valid: %d | Rejected: %d | Duration: %d ms | Throughput: %.0f rec/s",
                validCount, rejectedCount, durationMs, throughput));

        jobStatus("COMPLETED", jobName);
        jobLog.close();
    }

    // ── classification policy (simplified z/OS RC convention) ───────────────
    static String classify(int rc) {
        if (rc == RC_SUCCESS) return "SUCCESS";
        if (rc == RC_WARNING) return "WARNING";
        if (rc >= RC_FAILURE) return "FAILURE";
        return "UNKNOWN"; // valid non-negative RC not matching above (e.g. 1,2,3,5,6,7)
    }

    // ── record validation ────────────────────────────────────────────────────
    static String validate(String line) {
        String[] fields = line.split(",", -1);
        if (fields.length < 5) return "Insufficient fields (expected 5, got " + fields.length + ")";

        String jobId    = fields[0].trim();
        String jobNm    = fields[1].trim();
        String step     = fields[2].trim();
        String rcStr    = fields[3].trim();
        String durStr   = fields[4].trim();

        if (jobId.isEmpty())  return "Missing job_id";
        if (jobNm.isEmpty())  return "Missing job_name";
        if (step.isEmpty())   return "Missing step";

        int rc;
        try {
            rc = Integer.parseInt(rcStr);
        } catch (NumberFormatException e) {
            return "Invalid return_code: '" + rcStr + "'";
        }
        if (rc < 0) return "Negative return_code: " + rc;

        try {
            long dur = Long.parseLong(durStr);
            if (dur < 0) return "Negative duration_ms: " + dur;
        } catch (NumberFormatException e) {
            return "Invalid duration_ms: '" + durStr + "'";
        }

        return null; // valid
    }

    // ── lifecycle logging helpers ────────────────────────────────────────────
    private static void jobStatus(String state, String detail) {
        String msg = "[" + now() + "] JOB " + state + ": " + detail;
        System.out.println(msg);
        if (jobLog != null) { jobLog.println(msg); jobLog.flush(); }
    }

    private static void log(String message) {
        String msg = "[" + now() + "] " + message;
        System.out.println(msg);
        if (jobLog != null) { jobLog.println(msg); jobLog.flush(); }
    }

    private static String now() {
        return LocalTime.now().format(TIME_FMT);
    }
}
