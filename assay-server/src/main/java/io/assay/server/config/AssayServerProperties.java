package io.assay.server.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the optional HTTP server. */
@ConfigurationProperties(prefix = "assay.server")
public class AssayServerProperties {

    private String store = ".assay/store";
    private String output = ".assay/runs";
    private int workers = 1;
    private int retries = 0;
    private double timeoutSeconds = 30;
    private boolean collectFewShot = false;
    private int workerThreads = 2;
    private int jobLeaseSeconds = 60;
    private long pollMillis = 500;
    private int jobMaxAttempts = 1;
    private List<String> authTokens = new ArrayList<>();

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public double getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(double timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isCollectFewShot() {
        return collectFewShot;
    }

    public void setCollectFewShot(boolean collectFewShot) {
        this.collectFewShot = collectFewShot;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getJobLeaseSeconds() {
        return jobLeaseSeconds;
    }

    public void setJobLeaseSeconds(int jobLeaseSeconds) {
        this.jobLeaseSeconds = jobLeaseSeconds;
    }

    public long getPollMillis() {
        return pollMillis;
    }

    public void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    public int getJobMaxAttempts() {
        return jobMaxAttempts;
    }

    public void setJobMaxAttempts(int jobMaxAttempts) {
        this.jobMaxAttempts = jobMaxAttempts;
    }

    public List<String> getAuthTokens() {
        return authTokens;
    }

    public void setAuthTokens(List<String> authTokens) {
        this.authTokens = authTokens == null ? new ArrayList<>() : new ArrayList<>(authTokens);
    }
}
