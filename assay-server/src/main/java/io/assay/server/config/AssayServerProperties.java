package io.assay.server.config;

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
}
