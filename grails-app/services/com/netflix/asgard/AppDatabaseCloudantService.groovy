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

class AppDatabaseCloudantService implements AppDatabase, InitializingBean {
    def configService
    def baseRestUrl
    
    void afterPropertiesSet() {
        def user = configService.getCloudantAppDBUsername();
        baseRestUrl = "https://" + user + ".cloudant.com"
    }

    @Override
    public void createApplication(String name, String group, String type, String description, String owner, String email, MonitorBucketType monitorBucketType, String createTs, String updateTs) {
        def body = [
            group : group,
            type : type,
            description : description,
            owner : owner,
            email : email,
            monitorBucketType : monitorBucketType.name(),
            createTs : createTs,
            updateTs : updateTs
        ]
        
        def restClient = new RESTClient(baseRestUrl)
        restClient.auth.basic configService.getCloudantAppDBUsername(), configService.getCloudantAppDBPassword()

        HttpResponseDecorator resp = restClient.put(
            path : '/applications/' + name,
            requestContentType : 'application/json',
            body : body
        )
        log.warn "resp = " + resp
    }

    @Override
    public void updateApplication(String name, String group, String type, String description, String owner, String email, MonitorBucketType monitorBucketType, String updateTs) {
        def currentDoc = getApplicationDoc(name);
        String nowEpoch = new DateTime().millis as String
        def body = [
            group : group,
            type : type,
            description : description,
            owner : owner,
            email : email,
            monitorBucketType : monitorBucketType.name(),
            createTs : currentDoc.createTs,
            updateTs : nowEpoch,
            _rev : currentDoc._rev
        ]

        def restClient = new RESTClient(baseRestUrl)
        restClient.auth.basic configService.getCloudantAppDBUsername(), configService.getCloudantAppDBPassword()
        
        HttpResponseDecorator resp = restClient.put(
            path : '/applications/' + name,
            requestContentType : 'application/json',
            body : body
        )
        assert resp.status == 201
    }
    
    private String getRevEtagForExistingApp(String name) {
        def restClient = new RESTClient(baseRestUrl)
        restClient.auth.basic configService.getCloudantAppDBUsername(), configService.getCloudantAppDBPassword()

        HttpResponseDecorator resp = restClient.head(path : '/applications/' + name)
        String etag = resp.headers['Etag']?.value.replace('"', "")
        assert etag
        return etag
    }

    @Override
    public void deleteApplication(String name) {
        String etag = getRevEtagForExistingApp(name);

        def restClient = new RESTClient(baseRestUrl)
        restClient.auth.basic configService.getCloudantAppDBUsername(), configService.getCloudantAppDBPassword()
        
        HttpResponseDecorator resp = restClient.delete(path : '/applications/' + name, query : ['rev' : etag])
        assert resp.status == 200
    }

    @Override
    public AppRegistration getApplication(String appName) {
        def restClient = new RESTClient(baseRestUrl)
        restClient.auth.basic configService.getCloudantAppDBUsername(), configService.getCloudantAppDBPassword()
        AppRegistration reg

        try {
            restClient.get(
                    path : '/applications/' + appName,
                    contentType : 'application/json') { response, doc ->
                        log.warn 'doc = ' + doc
                        reg = new AppRegistration(
                                doc._id,
                                doc.group,
                                doc.type,
                                doc.description,
                                doc.owner,
                                doc.email,
                                doc.monitorBucketType,
                                doc.createTs,
                                doc.updateTs,
                                null)
                    }
            return reg
        }
        catch (HttpResponseException hre) {
            if (hre.statusCode == 404) {
                return null
            }
            throw hre
        }
    }
    
    private def getApplicationDoc(String appName) {
        def restClient = new RESTClient(baseRestUrl)
        restClient.auth.basic configService.getCloudantAppDBUsername(), configService.getCloudantAppDBPassword()

        try {
            restClient.get(
                    path : '/applications/' + appName,
                    contentType : 'application/json') { response, doc ->
                        return doc
                    }
        }
        catch (HttpResponseException hre) {
            if (hre.statusCode == 404) {
                return null
            }
            throw hre
        }
    }
    
    @Override
    public AppRegistration[] getAllApplications() {
        def restClient = new RESTClient(baseRestUrl)
        restClient.auth.basic configService.getCloudantAppDBUsername(), configService.getCloudantAppDBPassword()
        def docKeys = []
        restClient.get(
                path : '/applications/_all_docs',
                contentType : 'application/json') { response, allDocs ->
                    docKeys = allDocs.rows.collect { it.key }
                }
        log.warn docKeys
        def regs = []
        docKeys?.each { key ->
            restClient.get(
                    path : '/applications/' + key,
                    contentType : 'application/json') { reponse2, doc ->
                        log.warn 'doc = ' + doc
                        AppRegistration reg = new AppRegistration(
                                doc._id,
                                doc.group,
                                doc.type,
                                doc.description,
                                doc.owner,
                                doc.email,
                                doc.monitorBucketType,
                                doc.createTs,
                                doc.updateTs,
                                null)
                        regs.add(reg)
                    }
        }
        log.warn regs
        regs
    }

}
