package com.polopoly.ps.monitor;

public interface MonitoringServletMBean {

    long getCommitTime();

    long getSearchArticleTime();

    boolean isConnected();
}
