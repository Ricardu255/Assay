package io.assay.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spring Boot configuration for the optional Assay starter. */
@ConfigurationProperties(prefix = "assay")
public class AssayProperties {

    private final Store store = new Store();
    private int workers = 1;
    private int retries;
    private double timeoutSeconds = 30;
    private boolean collectFewShot;
    private String runIdentity;

    public Store getStore() {
        return store;
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

    public String getRunIdentity() {
        return runIdentity;
    }

    public void setRunIdentity(String runIdentity) {
        this.runIdentity = runIdentity;
    }

    /** Store selection: a local path for the file store, or a JDBC URL. */
    public static class Store {
        private String type = "file";
        private String path = ".assay/store";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
