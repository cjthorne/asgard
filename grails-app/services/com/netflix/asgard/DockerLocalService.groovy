/*
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard

import com.amazonaws.services.autoscaling.model.Activity;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.LaunchConfiguration;
import com.amazonaws.services.autoscaling.model.Instance as Instance_AutoScaling
import com.amazonaws.services.autoscaling.model.ScalingPolicy;
import com.amazonaws.services.autoscaling.model.ScheduledUpdateGroupAction;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest
import com.amazonaws.services.ec2.model.IpPermission
import com.amazonaws.services.ec2.model.Instance as Instance_EC2
import com.amazonaws.services.ec2.model.GroupIdentifier
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.netflix.asgard.auth.ApiToken
import com.netflix.asgard.model.AutoScalingGroupData;
import com.netflix.asgard.model.AutoScalingGroupHealthCheckType;
import com.netflix.asgard.model.AutoScalingProcessType;
import com.netflix.asgard.model.AutoScalingGroupBeanOptions;
import com.netflix.asgard.model.InstanceTypeData;
import com.netflix.asgard.model.LaunchConfigurationBeanOptions;
import com.netflix.asgard.model.ScalingPolicyData;
import com.netflix.asgard.model.Subnets;
import com.netflix.asgard.push.Cluster;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.springframework.beans.factory.InitializingBean

import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.AvailabilityZoneMessage
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.InstanceState
import com.amazonaws.services.ec2.model.Monitoring
import com.amazonaws.services.ec2.model.Placement
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Tag
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription;

import groovy.lang.Closure;
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.URLENC
import static groovyx.net.http.ContentType.JSON

import java.util.concurrent.atomic.AtomicReference


/**
 * Service class for working in Docker Local environment
 */
class DockerLocalService implements InitializingBean {

    def configService
    def secretService
    def taskService
	def caches
	def apiInterceptorService
	
	def availabilityZone = "docker-local-1"
    
    def dockerRestBase
    
    def asgControllerPort
    def asgControllerHost
	
    /**
     * Time based expiring cache of tokens that have triggered an email alert. This allows only one alert to be sent out
     * per configured interval.
     */
    private Cache<String, String> tokensAlertsSent

    void afterPropertiesSet() {
        tokensAlertsSent = new CacheBuilder()
                .expireAfterWrite(configService.apiTokenExpiryWarningIntervalMinutes, TimeUnit.MINUTES)
                .maximumSize(256)
                .build()
	    // Use MOP to intercept Aws service invocations.
		
		apiInterceptorService.setupServiceInterceptor(AwsEc2Service, DockerLocalService, this, awsEc2ServiceShouldInterceptInvokeMethod)
		apiInterceptorService.setupServiceInterceptor(AwsAutoScalingService, DockerLocalService, this, awsAutoScalingServiceShouldInterceptInvokeMethod)
		apiInterceptorService.setupServiceInterceptor(AwsLoadBalancerService, DockerLocalService, this, defaultShouldInterceptInvokeMethod)
        
        dockerRestBase = configService.dockerLocalBaseURL
        asgControllerPort = configService.grailsApplication.config.docker.local.asgController?.port ?: "56785"
        asgControllerHost = determineAsgControllerHostFromDockerInstances()
				
    }
	

	def defaultShouldInterceptInvokeMethod = {name, args ->  
		def shouldHandle = false
		if ((args!= null) && (args.length > 0) && (args[0] instanceof Region)) {
			def region = args[0] as Region
			if (region == Region.DOCKER_LOCAL_1) {
				shouldHandle = true
			}
		}
		return shouldHandle
	}

	def shouldHandleUserContext = {name, args, methodList -> 
		def shouldHandle = false
		if ((args!= null) && (args.length > 0) && (args[0] instanceof UserContext)) {
			def userContext = args[0] as UserContext
			def region = userContext.region
			if (region == Region.DOCKER_LOCAL_1) {
				if (name in methodList) {
					shouldHandle = true
				}
			}
		}
		return shouldHandle
	}
	
	def awsEc2ServiceInterceptNames = ["getImage",
		                               "getImageLaunchers",
									   "getSecurityGroup",
									   "getInstanceReservation"]
	def awsEc2ServiceShouldInterceptInvokeMethod = {name, args ->
		def shouldHandle = defaultShouldInterceptInvokeMethod(name,args) || 
		                   shouldHandleUserContext(name, args, awsEc2ServiceInterceptNames)
		return shouldHandle
	}

	
	def awsAutoScalingServiceInterceptNames = ["createLaunchConfigAndAutoScalingGroup",
		                                       "getAutoScalingGroup",
											   "getAutoScalingGroups",
											   "getLaunchConfiguration",
											   "buildAutoScalingGroupData",
											   "getAutoScalingGroupActivities",
											   "getScalingPoliciesForGroup",
											   "getScheduledActionsForGroup",
											   "getCluster",
											   "shouldGroupBeManuallySized",
											   "getLaunchConfigurationNamesForAutoScalingGroup",
											   "getLaunchConfigurations",
                                               "updateAutoScalingGroup"]
	def awsAutoScalingServiceShouldInterceptInvokeMethod = {name, args ->
		def shouldHandle = defaultShouldInterceptInvokeMethod(name,args) || 
		                   shouldHandleUserContext(name, args, awsAutoScalingServiceInterceptNames)
		return shouldHandle
	}

	// ---------------------------------------------------------------------
	//
	// EC2 Service Interceptor section.
	//
	// ---------------------------------------------------------------------
	
	private List<Instance_EC2> retrieveInstances(Region region) {
        log.debug 'retrieveInstances'
		//JSONArray json = restClientRightScaleService.getAsJson('https://my.rightscale.com/api/clouds/' + configService.getRightScaleCloudId() + '/instances.json?view=extended')
		//"localhost:4243/containers/json"
		def restClient = new RESTClient(dockerRestBase)
		List<Instance_EC2> instances = []
		def containers = []
		restClient.get(path: "/containers/json") {response, jsonContainers ->
			containers = jsonContainers.collect { 
				it
			}//.sort({ a, b -> dtp.parseDateTime(a.createDate) <=> dtp.parseDateTime(b.createDate) } as Comparator)
			def counter = 0
		}
		containers.each { container -> 
			restClient.get(path: "/containers/${container.Id}/json") {responseInner, inspectInfo ->
				def instance = new Instance_EC2(
					instanceId: inspectInfo.ID,
					imageId: inspectInfo.Image,
					instanceType: "Docker Container",
					launchTime: ISODateTimeFormat.dateTimeParser().parseDateTime(inspectInfo.State.StartedAt).toDate(),
					publicIpAddress : inspectInfo.NetworkSettings.IPAddress,
					privateIpAddress : inspectInfo.NetworkSettings.IPAddress,
					).withState(
						new InstanceState(code: 80, name: "Running")
					).withPlacement(
						new Placement(availabilityZone: "${availabilityZone}", groupName: '', tenancy: 'default')
					).withTags(
						[ new Tag(key: 'Name', value: "${inspectInfo.Name}") ]
					).withMonitoring(new Monitoring(state : 'disabled'))
				instances.add(instance)
			}
		}
		instances
	}

	List<String> getImageLaunchers(UserContext userContext, String imageId) {
			return ['dockerLocalLauncher']
	}

	Image getImage(UserContext userContext, String imageId, From preferredDataSource = From.AWS) {
		Image image = null
		if (imageId) {
			if (preferredDataSource == From.CACHE) {
				image = caches.allImages.by(userContext.region).get(imageId)
				if (image) { return image }
			}
			List<Image> images = retrieveImages(userContext.region).findAll {Image tmpImage ->
				tmpImage.imageId == imageId
			}
			image = Check.loneOrNone(images, Image)
			caches.allImages.by(userContext.region).put(imageId, image)
		}
		image
	}

	
	private List<Image> retrieveImages(Region region) {
        log.debug 'retrieveImages'
		def restClient = new RESTClient(dockerRestBase)
		List<Image> images = []
		restClient.get(path: "/images/json") { response, json ->
			json.each {
				def image = new Image()
				image.setName(it.RepoTags[0])
				image.setImageId(it.Id)
				image.setImageLocation(it.RepoTags[0])
				images.add(image)
				//println("Created new IMAGE!!!, ${image}")
			}//.sort({ a, b -> dtp.parseDateTime(a.createDate) <=> dtp.parseDateTime(b.createDate) } as Comparator)
		}
		images
	}
	
	SecurityGroup getSecurityGroup(UserContext userContext, String name, From from = From.AWS) {
		return retrieveSecurityGroups(userContext.region).find { it.groupName == name}
    }

	
	
	// -----------------------------------------------------------------------------------
	//
	//
	// AwsAutoScalingService interceptor section
	//
	//
	// -------------------------------------------------------------------------------------
	

	private List<AutoScalingGroup> retrieveAutoScalingGroups(Region region) {
        log.debug 'retrieveAutoScalingGroups'
		List<AutoScalingGroup> groups = []
		def restClient = getASGControllerRestClient(region)
		restClient.get(path: "${asgControllerPathPrefix}/asgs") {resp, data ->
			groups = data.collect { asgJson -> 
				//log.debug("retrieved autoScalingGroup JSON from controller => ${asgJson}")
				def newAsg = new AutoScalingGroup()
				newAsg.setAutoScalingGroupName(asgJson?.name ?: "undefined")
				newAsg.setLaunchConfigurationName(asgJson?.launch_configuration)
				newAsg.setMinSize(asgJson?.min_size)
				newAsg.setMaxSize(asgJson?.max_size)
				newAsg.setDesiredCapacity(asgJson?.desired_capacity)
				//log.debug("retrieved autoScalingGroup JSON from controller => ${asgJson}")
				newAsg
			}//.sort({ a, b -> dtp.parseDateTime(a.createDate) <=> dtp.parseDateTime(b.createDate) } as Comparator)
		}

		List<Instance_EC2> allInstances = retrieveInstances(region)
		// Now retrieve the instances from the asg, but be sure to create of type alias => Instance_AutoScaling
		groups.each {AutoScalingGroup asg ->
			restClient.get(path: "${asgControllerPathPrefix}/asgs/${asg.getAutoScalingGroupName()}/instances") {resp, data ->
				// Find the matching instance and add to auto scaling group
				def List<Instance_EC2> instancesInAsg = data.collect {asgInstanceData ->
						allInstances.find { Instance_EC2 instance ->
							instance.instanceId.startsWith(asgInstanceData.instance_id) 
						}
					}.flatten()
				def autoScalingInstances = instancesInAsg.collect { Instance_EC2 instanceEC2 ->
					Instance_AutoScaling instance = new Instance_AutoScaling(
						instanceId : instanceEC2.getInstanceId(),
						availabilityZone: availabilityZone,
						healthStatus: 'good', // TODO - get real instance data
						launchConfigurationName : asg.launchConfigurationName
						//lifeCycleState
						)
					instance
				}
				asg.setInstances(autoScalingInstances)
			}
		}
		
		log.debug 'Retrieving AutoSCalingGroups for ' + region.code + ' = ' + groups
		//[{
		//AutoScalingGroupName: acmeair_auth_service_tc7,
		//AutoScalingGroupARN: arn:aws:autoscaling:us-east-1:665469383253:autoScalingGroup:5c2670b0-ef31-4d7d-9a24-ff9ec4ab0dc5:autoScalingGroupName/acmeair_auth_service_tc7,
		//LaunchConfigurationName: acmeair_auth_service_tc7-20130805211228
		//MinSize: 0
		//MaxSize: 10
		//DesiredCapacity: 0
		//DefaultCooldown: 10
		//AvailabilityZones: [us-east-1a],
		//LoadBalancerNames: [],
		//HealthCheckType: EC2,
		//HealthCheckGracePeriod: 600,
		//Instances: [],
		//CreatedTime: Mon Aug 05 17:12:29 EDT 2013,
		//SuspendedProcesses: [],
		//VPCZoneIdentifier: ,
		//EnabledMetrics: [],
		//Tags: [],
		//TerminationPolicies: [Default],
		//},
		//{AutoScalingGroupName: acmeair_auth_service_wlp-v002,
		//AutoScalingGroupARN: ..
		groups
	}
	
	private void ensureUserDataIsDecodedAndTruncated(LaunchConfiguration launchConfiguration) {
		ensureUserDataIsDecoded(launchConfiguration)
		String userData = launchConfiguration.userData
		int maxLength = configService.cachedUserDataMaxLength
		if (userData.length() > maxLength) {
			launchConfiguration.userData = userData.substring(0, maxLength)
		}
	}

	private void ensureUserDataIsDecoded(LaunchConfiguration launchConfiguration) {
		launchConfiguration.userData = Ensure.decoded(launchConfiguration.userData)
	}
	
	private AtomicReference<String> asgControllerToken = new AtomicReference<String>("")
	
	def asgControllerPathPrefix = "/asgcc"
	
	def setASGContollerAuthHeader = {restClient ->
		restClient.defaultRequestHeaders['authorization'] = asgControllerToken.get()
		log.debug("Setting authorization header and token: ${restClient.defaultRequestHeaders}")
	}
	
	def asgControllerLogin = {RESTClient restClient ->
		//curl -X POST -H "Content-Type: application/json" -d '{"user":"user01","key":"key"}' http://172.17.0.4:56785/asgcc/login
		// RESP => {"status":"OK","token":"97d6bc4a-06eb-492e-b041-21c9a06c3601"}
		def asgControllerPropRoot = configService.grailsApplication.config.docker.local.asgController
		def user = asgControllerPropRoot.secret.user ?: "user01"
		def key = asgControllerPropRoot.secret.key ?: "key"
		def payload = """{"user":"${user}","key":"${key}"}"""
	    log.debug 'Performing login to ASG Controller for user = ${user} ...'
		def resp = restClient.post(
			path : "${asgControllerPathPrefix}/login",
			body : payload,
			requestContentType : URLENC)   {resp, data ->
                log.debug 'after response to ASG Controller for user = ${user} ...'
				assert resp.status == 200
				assert data.status == "OK"
				assert data.token.length() > 1
				asgControllerToken.set(data.token)
				setASGContollerAuthHeader(restClient)
				log.debug("Result of login to ASG Controller = ${data}")
	    }
        log.debug 'after login to ASG Controller for user = ${user} ...'
	}
	
    public initialLogin() {
        // TODO:  Make the code smarter to just login
        log.debug 'logging into auto scaling controller'
        def asgControllerRestBase = "http://${asgControllerHost}:${asgControllerPort}"
        def restClient = new RESTClient(asgControllerRestBase)
        asgControllerLogin(restClient)
    }
    
    public String determineAsgControllerHostFromDockerInstances() {
        log.debug 'determineAsgControllerHostFromDockerInstances'
        List<Instance_EC2> allInstances = retrieveInstances(Region.DOCKER_LOCAL_1)
        
        def asgControllerPropRoot = configService.grailsApplication.config.docker.local.asgController

        def asgControllerInstanceTagValueMatch = asgControllerPropRoot?.instanceTagValueMatch ?: "/NOTDEFINED"
        
        def Instance_EC2 asgControllerInstance
        allInstances.each { instance ->
            def tags = instance.tags
            tags.each { tag ->
                if (tag.value == asgControllerInstanceTagValueMatch) {
                    asgControllerInstance = instance
                }
            }
        }
        def host = asgControllerInstance?.getPrivateIpAddress() ?: "localhost"
        //log.debug("ASG Controller host = ${host}")
        host
    }
    
	def getASGControllerRestClient = { region ->
		def asgControllerRestBase = "http://${asgControllerHost}:${asgControllerPort}"
		def restClient = new RESTClient(asgControllerRestBase)
		setASGContollerAuthHeader(restClient)
        // TODO:  Reimplement the below for timed out login ins
		
//		// Test the client to make sure token is valid.
//		// Example => curl -X GET -H "Content-Type: application/json" -H "authorization: 74c34103-86dd-46bf-bbe4-318b29d65927"  http://172.17.0.4:56785/asgcc/asgs
//		def needToAuthenticate = false
//		try {
//			restClient.get(path : "${asgControllerPathPrefix}/asgs", requestContentType : JSON)
//			log.debug("Connection to ASG Controller tested OK.")
//		} catch (ConnectException ce) {
//            log.error(ce)
//		}catch (ex) {
//		    log.debug("Respons to test = ${ex.response.data}")
//			def errMsg = ex.response.data.message.toString().toLowerCase()
//			if (ex.response.status == 500 && (errMsg.contains("invalid token") || errMsg.contains("token expired"))) {
//				// Need to login
//				needToAuthenticate = true
//				log.debug("Invalid token, need to login to ASG Controller.")
//			 }else {
//				throw new Exception("Unexpected error trying to test connectivity to ASG Controller at ${restClient.getUri()}", ex)
//			 }
//		}
//		if (needToAuthenticate) {
//			asgControllerLogin(restClient)
//		}
		return restClient
	}

	List<LaunchConfiguration> retrieveLaunchConfigurations(Region region) {
		List<LaunchConfiguration> configs = []
		def restClient = getASGControllerRestClient(region)
		restClient.get(path: "${asgControllerPathPrefix}/lconfs") {response, lconfs ->
			configs = lconfs.collect { lconf -> 
				LaunchConfiguration config = new LaunchConfiguration(
					launchConfigurationName : lconf.name,
					launchConfigurationARN : "fakearn",
					imageId : lconf.image_id,
					keyName : lconf.key,
					securityGroups : ["fakesecuritygroup"],
				).withUserData('fakeuserdata'.encodeAsBase64())
				log.debug("retrieved launchConfigurations => ${config}")
				config
			}//.sort({ a, b -> dtp.parseDateTime(a.createDate) <=> dtp.parseDateTime(b.createDate) } as Comparator)
		}
		configs.each { ensureUserDataIsDecodedAndTruncated(it) }
		configs
	}
	
	private List<AvailabilityZone> retrieveAvailabilityZones(Region region) {
        log.debug 'retrieveAvailabilityZones'
		def List<AvailabilityZone> result
		def List<AvailabilityZone> zones = []
        // TODO: This should be returning the AZ's we defined in Microscaler
		//log.debug "instance = " + it
		def String zoneName = "${availabilityZone}"
		def String datacenterid = "local"
		def AvailabilityZone az = new AvailabilityZone(zoneName : zoneName, state : 'available', regionName : Region.DOCKER_LOCAL_1)
		def AvailabilityZoneMessage message = new AvailabilityZoneMessage(message: datacenterid)
		az.setMessages([message])
		zones.add(az)
        zones
		//result.sort { it.zoneName }
	}
	
	
	private List<SecurityGroup> retrieveSecurityGroups(Region region) {
		List<SecurityGroup> securityGroups = []
		def fakeSecurityGroup = new SecurityGroup(
			ownerId: '12345',
			groupName: 'docker-default',
			groupId: 'sg-fake',
			description: 'fake Docker Local security group',
			//IpPermissions: [{IpProtocol: -1, UserIdGroupPairs: [{UserId: 665469383253, GroupId: sg-f49f7b9b, }], IpRanges: [], }],
			//IpPermissionsEgress: [{IpProtocol: -1, UserIdGroupPairs: [], IpRanges: [0.0.0.0/0], }],
			vpcId: 'vpc-fake'
			// Tags: []
		).withIpPermissions([
			new IpPermission(
				ipProtocol: 'tcp',
				//userIdGroupPairs: [{UserId: 665469383253, GroupId: sg-f49f7b9b, }],
				//IpRanges: []
			).withFromPort(0).withToPort(100)
		])
		securityGroups.add(fakeSecurityGroup)
		securityGroups
	}
	
	private Collection<Subnet> retrieveSubnets(Region region) {
		return []
	}

	AutoScalingGroup getAutoScalingGroup(UserContext userContext, String name, From from = From.AWS) {
		if (!name) { return null }
		List<AutoScalingGroup> groups = getAutoScalingGroups(userContext, [name], from)
		Check.loneOrNone(groups, AutoScalingGroup)
	}

	Collection<AutoScalingGroup> getAutoScalingGroups(UserContext userContext) {
		caches.allAutoScalingGroups.by(userContext.region).list()
	}

	
	List<AutoScalingGroup> getAutoScalingGroups(UserContext userContext, Collection<String> names,
			From from = From.AWS) {
		if (names) {
			if (from == From.CACHE) {
				return names.collect { caches.allAutoScalingGroups.by(userContext.region).get(it) }.findAll { it != null }
			}
			List<AutoScalingGroup> groups = retrieveAutoScalingGroups(userContext.region).findAll {it.autoScalingGroupName in names}
			if (from == From.AWS) {
				// Update the ASG cache for all the requested ASG names including the ASGs that no longer exist.
				for (String name in names) {
					if (name) {
						// To remove non-existent ASGs from the cache, put the null group reference.
						AutoScalingGroup group = groups.find { it.autoScalingGroupName == name }
						caches.allAutoScalingGroups.by(userContext.region).put(name, group)
					}
				}
				return groups.collect { it.copy() }
			} else if (from == From.AWS_NOCACHE) {
				return groups
			}
		}
		[]
	}

    private def postToASGController = { UserContext userContext, String path, String msg, payload ->
		log.debug("Posting ${msg} request to ASG Controller => ${payload}...")
		def result = null
		def restClient = getASGControllerRestClient(userContext.region)
		try {
			def resp = restClient.post(
				path : "${asgControllerPathPrefix}${path}",
				body : payload,
				requestContentType : JSON)   {resp, data ->
					assert resp.status == 200
					log.debug("Successfully posted ${msg} request to ASG Controller, response.data => ${data}")
					result = data
			}
		}catch (ex) {
			def errMsg = "Error posting ${msg} request to ASG Controller => ${payload}, with error response => ${ex.response.data}"
			log.error(errMsg)
			throw new Exception(errMsg, ex)
		}
		return result
	}
    	
    private def putToASGController = { UserContext userContext, String path, String msg, payload ->
        log.debug("Posting ${msg} request to ASG Controller => ${payload}...")
        def result = null
        def restClient = getASGControllerRestClient(userContext.region)
        try {
            HttpResponseDecorator resp = restClient.put(
                path : "${asgControllerPathPrefix}${path}",
                requestContentType : 'application/json',
                body : payload)
        } catch (ex) {
            def errMsg = "Error posting ${msg} request to ASG Controller => ${payload}, with error response => ${ex.response.data}"
            log.error(errMsg)
            throw new Exception(errMsg, ex)
        }
        return result
    }

	private def requestToASGController = { UserContext userContext, String method, String path, String msg, payload ->
		log.debug("${method} ${msg} request to ASG Controller => ${payload}...")
		def result = null
		def restClient = getASGControllerRestClient(userContext.region)
		try {
			def resp = restClient."${method}"(
				path : "${asgControllerPathPrefix}${path}",
				body : payload,
				requestContentType : JSON)   {resp, data ->
					assert resp.status == 200
					log.debug("Successfully ${method} ${msg} request to ASG Controller, response.data => ${data}")
					result = data
			}
		}catch (ex) {
			def errMsg = "Error in ${method} ${msg} request to ASG Controller => ${payload}, with error response => ${ex.response.data}"
			log.error(errMsg)
			throw new Exception(errMsg, ex)
		}
		return result
	}

		
	LaunchConfiguration getLaunchConfiguration(UserContext userContext, String name, From from = From.AWS) {
        if (!name) { return null }
        if (from == From.CACHE) {
            return caches.allLaunchConfigurations.by(userContext.region).get(name)
        }
		List<LaunchConfiguration> launchConfigs = retrieveLaunchConfigurations(userContext.region).findAll { LaunchConfiguration launchConfig ->
			launchConfig.launchConfigurationName == name
		}
        if (launchConfigs.size() > 0) {
            LaunchConfiguration launchConfig = Check.lone(launchConfigs, LaunchConfiguration)
            ensureUserDataIsDecoded(launchConfig)
            return caches.allLaunchConfigurations.by(userContext.region).put(name, launchConfig)
        }
        null
    }

			
	private def createLaunchConfiguration = {UserContext userContext, LaunchConfigurationBeanOptions launchConfig ->
		def payload = [:]
		payload.name = launchConfig.launchConfigurationName
		payload.image_id = launchConfig.imageId
		payload.instances_type = launchConfig.instanceType
		payload.key = "key"
		postToASGController(userContext, "/lconfs", "create launch configuration", payload)
	}

	private def createAutoScalingGroupPayloadForDocker = {AutoScalingGroupBeanOptions asgOptions ->
		def payload = [:]
		payload.name = asgOptions.autoScalingGroupName
		payload.availability_zones = asgOptions?.availabilityZones ?: [availabilityZone]
		payload.launch_configuration = asgOptions.launchConfigurationName
		payload.min_size = asgOptions.minSize
		payload.max_size = asgOptions.maxSize
		payload.desired_capacity = asgOptions.desiredCapacity
		payload.scale_out_cooldown = 300 //asgOptions.defaultCooldown
		payload.scale_in_cooldown = 60 //
		payload.domain = "research.ibm.com" // TODO: Get from config
		return payload
	}
    	
	private def createAutoScalingGroupForDocker = {UserContext userContext, AutoScalingGroupBeanOptions asgOptions ->
		def payload = createAutoScalingGroupPayloadForDocker(asgOptions)
        postToASGController(userContext, "/asgs", "create auto scaling group", payload)
	}
    
    private def updateAutoScalingGroupForDocker = {UserContext userContext, AutoScalingGroupBeanOptions asgOptions ->
        def payload = createAutoScalingGroupPayloadForDocker(asgOptions)
        putToASGController(userContext, "/asgs/${asgOptions.autoScalingGroupName}", "update auto scaling group", payload)
    }

	def startAutoScalingGroup = {UserContext userContext, String asgName ->
		// Example curl request below for ASG name = 'test'
		//  curl -v -X PUT -H "Content-Type: application/json" -H "content-length: 0" -H "authorization: e4296df5-356b-4c0e-a6ca-a485f8708eac"  http://172.17.0.4:56785/asgcc/asgs/test/start
		log.debug("PUTting start request to ASG Controller...")
		def result = null
		def restClient = getASGControllerRestClient(userContext.region)
		def payload = []
		try {
			def resp = restClient.put(
				path : "${asgControllerPathPrefix}/asgs/${asgName}/start",
				body : payload, 
				requestContentType : JSON)
		}catch (ex) {
			//def errMsg = "Error PUTting request to ASG Controller => ${payload}, with error response => ${ex.response.data}"
			log.error("Error putting start to asg controller, ${ex}")
			throw ex
		}
		return result
	}

					
	CreateAutoScalingGroupResult createLaunchConfigAndAutoScalingGroup(UserContext userContext,
				AutoScalingGroup groupTemplate, LaunchConfiguration launchConfigTemplate,
				Collection<AutoScalingProcessType> suspendedProcesses, boolean enableChaosMonkey = false,
				Task existingTask = null) {
	
		CreateAutoScalingGroupResult result = new CreateAutoScalingGroupResult()
		String groupName = groupTemplate.autoScalingGroupName
		String launchConfigName = Relationships.buildLaunchConfigurationName(groupName)
		String msg = "Create Auto Scaling Group '${groupName}' in Docker local region."
		Subnets subnets = null//awsEc2Service.getSubnets(userContext)

		AutoScalingGroupBeanOptions groupOptions = AutoScalingGroupBeanOptions.from(groupTemplate, subnets)
		groupOptions.launchConfigurationName = launchConfigName
		groupOptions.suspendedProcesses = suspendedProcesses

		LaunchConfigurationBeanOptions launchConfig = LaunchConfigurationBeanOptions.from(launchConfigTemplate)
		launchConfig.launchConfigurationName = launchConfigName
		
		// Create the LaunchConfig
		createLaunchConfiguration(userContext, launchConfig)
		result.launchConfigName = launchConfigName
		result.autoScalingGroupName = groupName
		result.launchConfigCreated = true // should exist in ASG Controller now.
		
		// Create the AutoScalingGroup next
		createAutoScalingGroupForDocker(userContext, groupOptions)
		// Start the AutoScalingGroup
		
		result.autoScalingGroupCreated = true
		
		startAutoScalingGroup(userContext, groupName)
		
//			if (result.autoScalingGroupCreated && enableChaosMonkey) {
//				String cluster = Relationships.clusterFromGroupName(groupTemplate.autoScalingGroupName)
//				task.log("Enabling Chaos Monkey for ${cluster}.")
//				Region region = userContext.region
//				result.cloudReadyUnavailable = !cloudReadyService.enableChaosMonkeyForCluster(region, cluster)
//			}

		result
	}
				
				
	Cluster getCluster(UserContext userContext, String name, From from = From.AWS) {
		if (!name) { return null }
		if (from == From.CACHE) {
			return caches.allClusters.by(userContext.region).get(name)
		}
		// If the name contains a version number then remove the version number to get the cluster name
		String clusterName = Relationships.clusterFromGroupName(name)
		if (!clusterName) { return null }
		Collection<AutoScalingGroup> allGroupsSharedCache = getAutoScalingGroups(userContext)
		Cluster cluster = buildCluster(userContext, allGroupsSharedCache, clusterName)
		caches.allClusters.by(userContext.region).put(clusterName, cluster)
		cluster
	}
			
				
				
				
	private Collection<Cluster> buildClusters(Region region, Collection<AutoScalingGroup> allGroups) {
		UserContext userContext = UserContext.auto(region)
		Map<String, Cluster> clusterNamesToClusters = [:]
		allGroups.each { AutoScalingGroup group ->
			// If the name contains a version number then remove the version number to get the cluster name
			String clusterName = Relationships.clusterFromGroupName(group.autoScalingGroupName)

			// Make a cluster object only if we haven't made one yet that matches this ASG.
			if (!clusterNamesToClusters[clusterName]) {
				Cluster cluster = buildCluster(userContext, allGroups, clusterName, From.CACHE)
				if (cluster) {
					clusterNamesToClusters[clusterName] = cluster
				}
			}
		}

		clusterNamesToClusters.values() as List
	}
	
	Cluster buildCluster(UserContext userContext, Collection<AutoScalingGroup> allGroups, String clusterName,
		From loadAutoScalingGroupsFrom = From.AWS) {

		// Optimization: find the candidate ASGs that start with the cluster name to avoid dissecting every group name.
		List<AutoScalingGroup> candidates = allGroups.findAll { it.autoScalingGroupName.startsWith(clusterName) }
	
		// Later the ASG CachedMap should contain AutoScalingGroupData objects instead of AutoScalingGroup objects.
	
		// Find ASGs whose names equal the cluster name or the cluster name followed by a push number.
		List<AutoScalingGroup> groups = candidates.findAll {
			Relationships.clusterFromGroupName(it.autoScalingGroupName) == clusterName }
	
		Set<String> asgNamesForCacheRefresh = groups*.autoScalingGroupName as Set
		// If a separate Asgard instance recently created an ASG then it wouldn't have been found in the cache.
		asgNamesForCacheRefresh << clusterName
	
		// Reduce the ASG list to the ASGs that still exist in Amazon. As a happy side effect, update the ASG cache.
		groups = getAutoScalingGroups(userContext, asgNamesForCacheRefresh, loadAutoScalingGroupsFrom)
	
		if (groups.size()) {
			// This looks similar to buildAutoScalingGroupData() but it's faster to prepare the instance, ELB and AMI
			// lists once for the entire cluster than multiple times.
			List<String> instanceIds = groups.collect { it.instances }.collect { it.instanceId }.flatten()
			// Currently no loadbalancers
			Map<String, Collection<LoadBalancerDescription>> instanceIdsToLoadBalancerLists = instanceIds.collectEntries {
			   [it : []]	
			}
	
			List<MergedInstance> mergedInstances = retrieveInstances(userContext.region).findAll {
				it.instanceId in instanceIds
			}.collect {
				new MergedInstance(it, null)
			}

			Map<String, Image> imageIdsToImages = retrieveImages(userContext.region).collectEntries { 
				["${it.imageId}" : it]
			}
			List<AutoScalingGroupData> clusterGroups = groups.collect { AutoScalingGroup asg ->
				AutoScalingGroupData.from(asg, instanceIdsToLoadBalancerLists, mergedInstances, imageIdsToImages, [])
			}
	
			return new Cluster(clusterGroups)
		}
		null
  }
				
			
	AutoScalingGroupData buildAutoScalingGroupData(UserContext userContext, AutoScalingGroup group) {
		List<String> instanceIds = group.instances*.instanceId
		//TODO: Currently do not get the ApplicationInstance through DiscoveryService, consider adding back later.
		List<MergedInstance> mergedInstances = retrieveInstances(userContext.region).findAll {
			it.instanceId in instanceIds
		}.collect {
			new MergedInstance(it, null)
		}
		
		// Currently no loadbalancers
		Map<String, Collection<LoadBalancerDescription>> instanceIdsToLoadBalancerLists = instanceIds.collectEntries {
		   [it : []]	
		}
		
		List<Image> images = retrieveImages(userContext.region)
		Map<String, Image> imageIdsToImages = images.collectEntries { 
			["${it.imageId}" : it]
		}

		Collection<ScalingPolicyData> scalingPolicies = []
		AutoScalingGroupData.from(group, instanceIdsToLoadBalancerLists, mergedInstances, imageIdsToImages, scalingPolicies)
	}
	
	List<Activity> getAutoScalingGroupActivities(UserContext userContext, String name, Integer maxTotalActivities) {
		List<Activity> activities = []
		activities
	}
	
	List<ScalingPolicy> getScalingPoliciesForGroup(UserContext userContext, String autoScalingGroupName) {
		return []
	}
	
	List<ScheduledUpdateGroupAction> getScheduledActionsForGroup(UserContext userContext, String autoScalingGroupName) {
		return []
	}
	
	Reservation getInstanceReservation(UserContext userContext, String instanceId) {
		if (!instanceId) { return null }
		 Reservation res = new Reservation(
			  reservationId: 'r-fakereservationid',
			  ownerId: '665469383253',
			  groups: [new GroupIdentifier(groupName: 'fakegroup', groupId: 'sg-fakegroupid')],
			  groupNames: ['fakegroup'],
			  instances : [ retrieveInstances(userContext.region).find { instanceId.contains(it.instanceId) } ]
		 )
		 return res
		
//		Reservation reservation = Check.lone(reservations, Reservation)
//		caches.allInstances.by(userContext.region).put(instanceId, Check.lone(reservation.instances, Instance))
//		reservation
	}


	/**
	 * Checks whether the auto scaling group is currently set up for automated dynamic scaling or not.
	 *
	 * @param userContext who, where, why
	 * @param group the auto scaling group to analyze
	 * @return true if the group's desired size should be set manually because alarms are not causing dynamic scaling
	 */
	boolean shouldGroupBeManuallySized(UserContext userContext, AutoScalingGroup group) {
		false
	}
    
    AutoScalingGroup updateAutoScalingGroup(UserContext userContext, AutoScalingGroupData autoScalingGroupData,
            Collection<AutoScalingProcessType> suspendProcessTypes = [],
            Collection<AutoScalingProcessType> resumeProcessTypes = [], existingTask = null) {
        AutoScalingGroup group = getAutoScalingGroup(userContext, autoScalingGroupData.autoScalingGroupName)

        UpdateAutoScalingGroupRequest request = BeanState.ofSourceBean(autoScalingGroupData).injectState(
                new UpdateAutoScalingGroupRequest()).withHealthCheckType(autoScalingGroupData.healthCheckType.name())

        /*
        if (!autoScalingGroupData.availabilityZones) {
            // No zones were selected because there was no chance to change them. Keep the old zones.
            request.availabilityZones = group.availabilityZones
        }*/

        taskService.runTask(userContext, "Update Autoscaling Group '${autoScalingGroupData.autoScalingGroupName}'", { Task task ->
            log.error '** should do work here'
            
            AutoScalingGroupBeanOptions groupBean = new AutoScalingGroupBeanOptions();
            groupBean.autoScalingGroupName = autoScalingGroupData.autoScalingGroupName
            groupBean.launchConfigurationName = autoScalingGroupData.launchConfigurationName
            groupBean.minSize = autoScalingGroupData.minSize
            groupBean.maxSize = autoScalingGroupData.maxSize
            groupBean.desiredCapacity = autoScalingGroupData.desiredCapacity
            groupBean.availabilityZones = autoScalingGroupData.availabilityZones
            
            updateAutoScalingGroupForDocker(userContext, groupBean)
        }, Link.to(EntityType.autoScaling, autoScalingGroupData.autoScalingGroupName), existingTask)

        // Refresh auto scaling group and cluster cache
        group = getAutoScalingGroup(userContext, autoScalingGroupData.autoScalingGroupName)
        getCluster(userContext, autoScalingGroupData.autoScalingGroupName)
        group
    }

	Collection<String> getLaunchConfigurationNamesForAutoScalingGroup(UserContext userContext,
			String autoScalingGroupName) {
		Set<String> launchConfigNamesForGroup = [] as Set
		String currentLaunchConfigName = getAutoScalingGroup(userContext, autoScalingGroupName).launchConfigurationName
		if (currentLaunchConfigName) {
			launchConfigNamesForGroup.add(currentLaunchConfigName)
		}
		def pat = ~/^${autoScalingGroupName}-\d{12,}$/
		launchConfigNamesForGroup.addAll(getLaunchConfigurations(userContext).findAll {
			it.launchConfigurationName ==~ pat }.collect { it.launchConfigurationName })
		new ArrayList<String>(launchConfigNamesForGroup)
	}
			
	Collection<LaunchConfiguration> getLaunchConfigurations(UserContext userContext) {
		caches.allLaunchConfigurations.by(userContext.region).list()
	}
	
	/**
	 * Update an existing ASG.
	 *
	 * @param userContext who made the call, why, and in what region
	 * @param asgName name of the ASG
	 * @param transform changes from the old ASG to the updated version
	 * @return the state of the updated ASG
	 */
	AutoScalingGroupBeanOptions updateAutoScalingGroup(UserContext userContext, String asgName,
			Closure transform) {
		AutoScalingGroup originalAsg = getAutoScalingGroup(userContext, asgName)
		if (!originalAsg) { return null }
		originalAsg
		Subnets subnets = null//awsEc2Service.getSubnets(userContext)
		AutoScalingGroupBeanOptions originalAsgOptions = AutoScalingGroupBeanOptions.from(originalAsg, subnets)
		AutoScalingGroupBeanOptions newAsgOptions = AutoScalingGroupBeanOptions.from(originalAsgOptions)
		transform(newAsgOptions)
		newAsgOptions.autoScalingGroupName = asgName
		
		// Now update 
		def payload = createAutoScalingGroupPayloadForDocker(newAsgOptions)
		requestToASGController(userContext, "put", "/asgs/${newAsgOptions.autoScalingGroupName}", "update auto scaling group", payload)

//		SuspendProcessesRequest suspendProcessesRequest = originalAsgOptions.
//				getSuspendProcessesRequestForUpdate(newAsgOptions.suspendedProcesses)
//		if (suspendProcessesRequest) {
//			awsClient.by(userContext.region).suspendProcesses(suspendProcessesRequest)
//		}
//		ResumeProcessesRequest resumeProcessesRequest = originalAsgOptions.
//				getResumeProcessesRequestForUpdate(newAsgOptions.suspendedProcesses)
//		if (resumeProcessesRequest) {
//			awsClient.by(userContext.region).resumeProcesses(resumeProcessesRequest)
//		}
//		awsClient.by(userContext.region).updateAutoScalingGroup(newAsgOptions.getUpdateAutoScalingGroupRequest(subnets))
		newAsgOptions
	}

		

			
				
				
				
	//-------------------------------------------------------------
	// AwsLoadBalancerService interceptors
    //
    //-------------------------------------------------------------				
	private List<LoadBalancerDescription> retrieveLoadBalancers(Region region) {
		def balancers = []
		return balancers
	}		
}
