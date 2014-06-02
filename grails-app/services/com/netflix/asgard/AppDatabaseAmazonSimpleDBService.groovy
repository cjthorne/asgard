package com.netflix.asgard

import java.util.Collection;
import java.util.List;

import com.amazonaws.services.simpledb.AmazonSimpleDB
import com.amazonaws.services.simpledb.model.Item
import com.amazonaws.services.simpledb.model.ReplaceableAttribute
import com.netflix.asgard.model.MonitorBucketType
import org.springframework.beans.factory.InitializingBean
import com.amazonaws.services.simpledb.AmazonSimpleDB
import com.amazonaws.services.simpledb.model.CreateDomainRequest
import com.amazonaws.services.simpledb.model.DeleteAttributesRequest
import com.amazonaws.services.simpledb.model.Item
import com.amazonaws.services.simpledb.model.PutAttributesRequest
import com.amazonaws.services.simpledb.model.ReplaceableAttribute
import com.amazonaws.services.simpledb.model.SelectRequest
import com.amazonaws.services.simpledb.model.SelectResult
import com.amazonaws.AmazonServiceException
import org.joda.time.DateTime
import com.netflix.asgard.AppDatabase

class AppDatabaseAmazonSimpleDBService implements AppDatabase, InitializingBean {
    /** The name of SimpleDB domain that stores the cloud application registry. */
    private String domainName
    
    AmazonSimpleDB simpleDbClient
    def awsClientService
    def configService
    
    String selectAllStatement() {
        "select * from ${domainName} limit 2500"
    }

    String selectOneStatement() {
        "select * from ${domainName} where itemName()="
    }

    void afterPropertiesSet() {
        domainName = configService.applicationsDomain
        // Applications are stored only in the default region, so no multi region support needed here.
        simpleDbClient = simpleDbClient ?: awsClientService.create(AmazonSimpleDB)
    }

    private Collection<AppRegistration> retrieveApplications() {
        //List<Item> items = awsSimpleDbService.selectAll(domainName).sort { it.name.toLowerCase() }
        List<Item> items = runQuery(selectAllStatement()).sort { it.name.toLowerCase() }
        items.collect { AppRegistration.from(it) }
    }

    private List<Item> runQuery(String queryString) {
        List<Item> appItems = []
        try {
            SelectResult result = simpleDbClient.select(new SelectRequest(queryString, true))
            while (true) {
                appItems.addAll(result.items)
                String nextToken = result.nextToken
                if (nextToken) {
                    sleep 500
                    result = simpleDbClient.select(new SelectRequest(queryString, true).withNextToken(nextToken))
                } else {
                    break
                }
            }
        } catch (AmazonServiceException ase) {
            if (ase.errorCode == 'NoSuchDomain') {
                simpleDbClient.createDomain(new CreateDomainRequest(domainName))
            } else {
                throw ase
            }
        }
        appItems
    }

    private Collection<ReplaceableAttribute> buildAttributesList(String group, String type, String description,
        String owner, String email, MonitorBucketType monitorBucketType, Boolean replaceExistingValues) {
        
        Check.notNull(monitorBucketType, MonitorBucketType, 'monitorBucketType')
        String nowEpoch = new DateTime().millis as String
        Collection<ReplaceableAttribute> attributes = []
        attributes << new ReplaceableAttribute('group', group ?: '', replaceExistingValues)
        attributes << new ReplaceableAttribute('type', Check.notEmpty(type), replaceExistingValues)
        attributes << new ReplaceableAttribute('description', Check.notEmpty(description), replaceExistingValues)
        attributes << new ReplaceableAttribute('owner', Check.notEmpty(owner), replaceExistingValues)
        attributes << new ReplaceableAttribute('email', Check.notEmpty(email), replaceExistingValues)
        attributes << new ReplaceableAttribute('monitorBucketType', monitorBucketType.name(), replaceExistingValues)
        attributes << new ReplaceableAttribute('updateTs', nowEpoch, replaceExistingValues)
        return attributes
    }
            
    @Override
    public void createApplication(String name, String group, String type, String description, String owner, String email, MonitorBucketType monitorBucketType, String createTs, String updateTs) {
        Collection<ReplaceableAttribute> attributes = buildAttributesList(group, type, description, owner, email,
            monitorBucketType, false)
        attributes << new ReplaceableAttribute('createTs', createTs, false)
        simpleDbClient.putAttributes(new PutAttributesRequest().withDomainName(domainName).
            withItemName(name.toUpperCase()).withAttributes(attributes))
    }

    @Override
    public void updateApplication(String name, String group, String type, String description, String owner, String email, MonitorBucketType monitorBucketType, String updateTs) {
        Collection<ReplaceableAttribute> attributes = buildAttributesList(group, type, description, owner, email,
                monitorBucketType, true)
        simpleDbClient.putAttributes(new PutAttributesRequest().withDomainName(domainName).
            withItemName(name.toUpperCase()).withAttributes(attributes))
    }
    
    @Override
    public void deleteApplication(String name) {
        simpleDbClient.deleteAttributes(new DeleteAttributesRequest(domainName, name.toUpperCase()))
    }

    @Override
    public AppRegistration getApplication(String appName) {
        assert !(appName.contains("'")) // Simple way to avoid SQL injection
        String queryString = "${selectOneStatement()}'${appName.toUpperCase()}'"
        List<Item> items = runQuery(queryString)
        AppRegistration appRegistration = AppRegistration.from(Check.loneOrNone(items, 'items'))
    }
    
    @Override
    public AppRegistration[] getAllApplications() {
        retrieveApplications()
    }

}
