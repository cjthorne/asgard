package com.netflix.asgard

import com.netflix.asgard.model.MonitorBucketType;

interface AppDatabase {
    void createApplication(String name, String group, String type, String description,
        String owner, String email, MonitorBucketType monitorBucketType, String createTs, String updateTs)
    void updateApplication(String name, String group, String type, String description,
        String owner, String email, MonitorBucketType monitorBucketType, String updateTs)
    void deleteApplication(String name)
    AppRegistration getApplication(String appName)
    AppRegistration[] getAllApplications()
}
