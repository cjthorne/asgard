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

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.netflix.asgard.auth.ApiToken
import java.util.concurrent.TimeUnit
import org.joda.time.DateTime
import org.springframework.beans.factory.InitializingBean


class InterceptorEntry {
	Class interceptedClazz
	Class interceptorClazz
	Object target
	Closure shouldInterceptInvokeMethod
}

/**
 * Service class for validating {@link ApiToken} objects
 */
class ApiInterceptorService implements InitializingBean {

	def configService
	def emailerService
	def secretService

	/**
	 * Time based expiring cache of tokens that have triggered an email alert. This allows only one alert to be sent out
	 * per configured interval.
	 */
	private Cache<String, String> tokensAlertsSent

	void afterPropertiesSet() {
	}

	/**
	 * Checks if an API token is valid for any of the encryption keys specified in {@link SecretService}.
	 *
	 * @param apiToken A token object to check.
	 * @return true if the token is valid for any of the encryption keys, false otherwise.
	 */
	boolean tokenValid(ApiToken apiToken) {
		secretService.apiEncryptionKeys.find { String encryptionKey -> apiToken.isValid(encryptionKey) } != null
	}

	/**
	 * Sends a warning email if an API token is near the specified warning threshold.
	 *
	 * @param apiToken The token to check.
	 */
	void checkExpiration(ApiToken apiToken) {
		DateTime expiryWarningThreshold = new DateTime().plusDays(configService.apiTokenExpiryWarningThresholdDays)
		if (apiToken.expires.isBefore(expiryWarningThreshold) &&
		!tokensAlertsSent.getIfPresent(apiToken.credentials)) {
			emailerService.sendUserEmail(apiToken.email,
					"${configService.canonicalServerName} API key is about to expire",
					"The following ${configService.canonicalServerName} API key is about to expire:\n\n" +
					"Key: ${apiToken.credentials}\n" +
					"Purpose: ${apiToken.purpose}\n" +
					"Registered by: ${apiToken.username}\n" +
					"Expires: ${apiToken.expiresReadable}\n\n" +
					"Please generate a new token.")
			tokensAlertsSent.put(apiToken.credentials, apiToken.credentials)
		}
	}


	private interceptorMap = [:]

	def setupServiceInterceptor(Class interceptedClazz, Class interceptorClazz, Object target, Closure shouldInterceptInvokeMethod) {
		def msg = "${Meta.pretty(interceptorClazz)} Interceptor to intercept invocations for the ${Meta.pretty(interceptedClazz)}"
		log.debug("BANG!!!!!!!!!!!!Setting up ${msg}")

		
		// Make sure a map exists for the interceptedClazz
		if (interceptorMap[(interceptedClazz)] == null) {
			interceptorMap[(interceptedClazz)] = [:]
		}
		
		// Create new InterceptorEntry
		def interceptorEntry = new InterceptorEntry(interceptedClazz : interceptedClazz,
			                  interceptorClazz : interceptorClazz, 
							  target : target,
							  shouldInterceptInvokeMethod : shouldInterceptInvokeMethod)
		interceptorMap[(interceptedClazz)][(interceptorClazz)] = interceptorEntry 

		log.debug("current interceptorMap=${interceptorMap}")

		interceptedClazz.metaClass.invokeMethod = {String name, args ->
			
			// Loop through each defined interceptor's shouldHandle method, making sure only 1 handler
			// is chosen.  If more, throw an exception.
			log.debug("inteceprtorMap inside closure => ${this.interceptorMap}")
			
			def entriesToInvoke = this.interceptorMap[(interceptedClazz)].values().findAll { InterceptorEntry entry ->
				return entry.shouldInterceptInvokeMethod(name, args)
			}
			
			if (entriesToInvoke.size > 1) {
				log.warn("Expect 1 interceptor, found ${entriesToInvoke.size}")
				entriesToInvoke each { InterceptorEntry e ->
					log.warn("Interceptor => interceptedClazz=${e.interceptedClazz}, interceptorClazz=${e.interceptorClazz}, target=${e.target}")
				}
				throw new Exception("More than one handler found for interceptedClazz=${interceptedClazz}")
			} else if (entriesToInvoke.size == 1) {
			    // There is a single interceptor registered.
			    def InterceptorEntry interceptor = entriesToInvoke[0]
				def validServiceMethod = interceptor.interceptorClazz.metaClass.getMetaMethod(name, args)
				if (validServiceMethod != null) {
					log.debug("Call to $name intercepted for ${msg}")
					validServiceMethod.invoke(interceptor.target,  args)
				} else {
					// Not implemented for the docker local region.
					log.debug("WARNING:  $name not defined for ${msg}")
				}
			}else{
			   log.debug("Delegating to default service for => interceptedClazz=${interceptedClazz}, name=${name},args=${args}")
			   interceptedClazz.metaClass.getMetaMethod(name, args).invoke(delegate, args)
			}
		}
		log.debug("Completed setting ${msg}")
	}


}
