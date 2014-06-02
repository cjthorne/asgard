package com.netflix.asgard

import java.util.Collection;
import java.util.List;

import com.netflix.asgard.model.MonitorBucketType

import org.springframework.beans.factory.InitializingBean
import org.joda.time.DateTime

import com.netflix.asgard.AppDatabase

import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.HttpResponseException
import groovyx.net.http.RESTClient

import com.mongodb.BasicDBObject
import com.mongodb.DB
import com.mongodb.DBCollection
import com.mongodb.DBCursor
import com.mongodb.MongoClient

class AppDatabaseMongoService implements AppDatabase, InitializingBean {
    def configService
    DBCollection applications
    def grailsApplication
    
    void afterPropertiesSet() {
        String mongoHost = grailsApplication.config.appDatabase.mongo.host?: 'localhost'
        int mongoPort = grailsApplication.config.appDatabase.mongo.port?: 27017
        MongoClient mongoClient = new MongoClient(mongoHost, mongoPort)
        DB db = mongoClient.getDB('applications')
        applications = db.getCollection('applications')
    }

    @Override
    public void createApplication(String name, String group, String type, String description, String owner, String email, MonitorBucketType monitorBucketType, String createTs, String updateTs) {
        BasicDBObject doc = new BasicDBObject("_id", name)
        .append('group', group)
        .append('type', type)
        .append('description',  description)
        .append('owner', owner)
        .append('email', email)
        .append('monitorBucketType', monitorBucketType.name())
        .append('createTs', createTs)
        .append('updateTs', updateTs)
        
        applications.insert(doc)
    }

    @Override
    public void updateApplication(String name, String group, String type, String description, String owner, String email, MonitorBucketType monitorBucketType, String updateTs) {
        BasicDBObject query = new BasicDBObject("_id", name)
        
        DBCursor cursor = applications.find(query);
        
        if (cursor.hasNext()) {
            BasicDBObject doc = cursor.next();
            applications.update(doc, new BasicDBObject ('$set', new BasicDBObject(
                'group', group)
                .append('type', type)
                .append('description',  description)
                .append('owner', owner)
                .append('email', email)
                .append('monitorBucketType', monitorBucketType.name())
                .append('updateTs', updateTs)))
        }
    }
    
    @Override
    public void deleteApplication(String name) {
        BasicDBObject doc = new BasicDBObject('_id', name)
        
        applications.findAndRemove(doc)
    }

    @Override
    public AppRegistration getApplication(String appName) {
        BasicDBObject doc = new BasicDBObject('_id', appName)
        
        DBCursor cursor = applications.find(doc)
        AppRegistration app
        if (cursor.hasNext()) {
            BasicDBObject resp = cursor.next()
            app = convertDocToAppRegistration(resp);
        }
        app
    }
    
    @Override
    public AppRegistration[] getAllApplications() {
        DBCursor cursor = applications.find()
        List<AppRegistration> regs = []
        while (cursor.hasNext()) {
            BasicDBObject doc = cursor.next()
            regs.add(convertDocToAppRegistration(doc))
        }
        regs.toArray()
    }
    
    private convertDocToAppRegistration(BasicDBObject doc) {
        AppRegistration reg = new AppRegistration(
            doc.getString('_id'),
            doc.getString('group'),
            doc.getString('type'),
            doc.getString('description'),
            doc.getString('owner'),
            doc.getString('email'),
            doc.getString('monitorBucketType'),
            doc.getString('createTs'),
            doc.getString('updateTs'),
            null
        )
        reg
    }
}
