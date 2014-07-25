<%--

    Copyright 2012 Netflix, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

--%>
<html>
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
  <meta name="layout" content="main"/>
  <title>${image.id} ${image.name} Image</title>
</head>
<body>
  <div class="body">
    <h1>Image Details</h1>
    <g:if test="${flash.message}">
      <div class="message">${flash.message}</div>
    </g:if>
    <g:hasErrors bean="${cmd}">
      <div class="errors">
        <g:renderErrors bean="${cmd}" as="list"/>
      </div>
    </g:hasErrors>
    <div class="buttons">
      <g:form>
        <input type="hidden" name="id" value="${image.id}"/>
        <g:link class="edit" action="edit" params="[id:image.id]">Edit Image Attributes</g:link>
        <g:if test="${accountName == env}">
          <g:buttonSubmit class="delete" action="delete" value="Delete Image"
                          data-warning="Really delete image '${image.id}' with name '${image.name}'?" />
        </g:if>
        <g:else>
          <g:buttonSubmit disabled="true" class="delete" action="ignore" value="This image can only be deleted in ${accountName}"/>
        </g:else>
        <g:link class="push" action="prelaunch" params="[id:image.id]">Prepare to Launch Image Instance</g:link>
      </g:form>
    </div>
    <div class="dialog">
      <table>
        <tbody>
        <tr class="prop">
          <td class="name">ID:</td>
          <td class="value">${image.id}</td>
        </tr>
        <tr class="prop">
          <td class="name">Name:</td>
          <td class="value">${image.name}</td>
        </tr>
        <tr class="prop">
          <td class="name">Description:</td>
          <td class="value">${image.name}</td>
        </tr>
        <tr class="prop">
          <td class="name">Location:</td>
          <td class="value">${image.metadata['imageLocation']}</td>
        </tr>
        <tr class="prop">
          <td class="name">Architecture:</td>
          <td class="value">${image.metadata['architecture']}</td>
        </tr>
        <tr class="prop">
          <td class="name">Platform:</td>
          <td class="value">${image.metadata['platform']}</td>
        </tr>
        <tr class="prop">
          <td class="name">Type:</td>
          <td class="value">${image.metadata['imageType']}</td>
        </tr>
        <tr class="prop">
          <td class="name">State:</td>
          <td class="value">${image.status}</td>
        </tr>
        <tr class="prop">
          <td class="name">Owner:</td>
          <td class="value">${accountName} (${image.metadata['ownerId']})</td>
        </tr>
        <tr class="prop">
          <td class="name">Launch Permissions:</td>
          <td class="value">[<g:each status="i" var="u" in="${launchUsers}">${i == 0 ? '' : ', '}${accounts[u]} (${u})</g:each>]</td>
        </tr>
        <tr class="prop">
          <td class="name">Kernel ID:</td>
          <td class="value">${image.metadata['kernelId']}</td>
        </tr>
        <tr class="prop">
          <td class="name">Ramdisk ID:</td>
          <td class="value">${image.metadata['ramdiskId']}</td>
        </tr>
        <g:if test="${snapshotId}">
            <tr class="prop">
                <td class="name">Snapshot:</td>
                <td class="value"><g:linkObject name="${snapshotId}" type="snapshot"/></td>
            </tr>
        </g:if>
        <tr class="prop">
          <td class="name">Block Device Mappings:</td>
          <td class="value">
            <g:each var="blockDeviceMapping" in="${image.metadata['blockDeviceMappings']?.sort { it.deviceName }}">
              <div>${blockDeviceMapping}</div>
            </g:each>
          </td>
        </tr>
        <g:render template="/common/showTags2" model="[tags: image.metadata['tags']]"/>
        <tr class="prop">
          <td><h2>Referenced From</h2></td>
        </tr>
        <tr class="prop">
          <td><h2>Pattern Matches</h2></td>
        </tr>
        </tbody>
      </table>
    </div>
  </div>
</body>
</html>
