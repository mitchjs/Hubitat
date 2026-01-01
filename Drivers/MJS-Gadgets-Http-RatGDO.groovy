/*
 * RatGDO (Homekit FW), using HTTP access only
 * By: Mitch Solomon | mitchjs@rush2112.net
 * REQUIRES HOMEKIT FW: https://github.com/ratgdo/homekit-ratgdo
 *
 *   v1.0.0 - Initial Release
 */


import groovy.transform.Field

@Field static boolean shouldReconnect = false
@Field static boolean refreshNeeded = false

metadata {
    definition(name: "Homekit-RATGDO (http)", namespace: "MJS Gadgets", author: "MitchJS", importUrl: "https://raw.githubusercontent.com/mitchjs/Hubitat/refs/heads/main/Drivers/MJS-Gadgets-Http-RatGDO.groovy") {
        capability "Garage Door Control"
        capability "Refresh"
        capability "Initialize"
        
        command "LightOn"
        command "LightOff"
        command "RemotesEnabled"
        command "RemotesDisabled"
        
        //command "testCode"
        
        //attribute "door", "enum", ["unknown", "open", "closing", "closed", "opening"]
        attribute "light", "enum", ["on","off"]
        attribute "remotes", "enum", ["disabled","enabled"]
        attribute "availability", "enum", ["offline","online"]
        attribute "obstruction", "enum", ["obstructed","clear"]
        attribute "upTime", "string", ["0:00:00:00"]
        attribute "lastDoorActivity", "string"
        attribute "eventStreamStatus", "string"
        attribute "networkStatus", "enum", ["offline","online"]
    }
}

preferences {
	input name: "ipAddr", type: "text", title: "IP Address of RatGDO (homekit fw)", required: true          
    input name: "logLevel",title: "Logging Level", multiple: false, required: true, type: "enum", options: getLogLevels(), submitOnChange : false, defaultValue : "1"
}

def testCode() {
}

def initialize() {
    infolog("initialize() called")
 
    // create component child light
    def currentchild = getChildDevices()?.find { it.deviceNetworkId == "${device.deviceNetworkId}-light"}
    if (currentchild == null)
    {
    	addChildDevice("hubitat", "Generic Component Switch", "${device.deviceNetworkId}-light", [name: "${device.displayName}-light", isComponent: true])
    }
        
    // make sure we got an IP
    if (!settings.ipAddr) {
        infolog("No IP Address in prefs")
        return
    }
    
    // temporally prevent reconnection
    if (device.currentValue("eventStreamStatus") == "Connected") {
        shouldReconnect = false
    }
    
    sendEvent(name:"networkStatus", value: "offline")
    
    sendEvent(name: "eventStreamStatus", value: "Connecting", descriptionText:"${device.displayName} eventStreamStatus is Connecting")

    infolog("initialize() called, request subscribe to SSE")
        
    // set up for subscribe
    params = [
        uri: "http://${settings.ipAddr}",
        contentType: "text/html",
        path: "/rest/events/subscribe",
        query: [id : "${device.id}", "heartbeat" : "30"],
        body: "",
        timeout: 5
    ]
    
    try {
        // get event subscription url
        httpGet(params) { resp ->
            debuglog "resp.status.value=${resp.status.value}"
            if (resp.status.value == 200) {
                infolog("initialize() subscribing to events")
                // build event url        
                url = "http://${settings.ipAddr}${resp.data}?id=${device.id}"
                // connect to SSE
                interfaces.eventStream.connect(url, [
                    pingInterval: 10,
                    readTimeout: 60,
                    headers:["Accept": "text/event-stream"],
                    rawData: false
                ]) 
                
                refresh()
            }
        }
    }
    catch (Exception e) {
        log.error "initialize() Execption: ${e}"

        runIn(10, initialize)
    }
    
    // if logs are in "Debug" turn down to "Info" after an hour
    logL = logLevel.toInteger()
    if (logL == 2) runIn(3600,logsOff)
}

def updated() {
    infolog("updated() called")
    
    if (device.currentValue("eventStreamStatus") == "Connected") /* ||
        device.currentValue("eventStreamStatus") == "Connecting") */ {
        	sendEvent(name: "eventStreamStatus", value: "Disconnecting", descriptionText:"${device.displayName} eventStreamStatus is Disconnecting")
    		interfaces.eventStream.close()
        	infolog("interfaces.eventStream.close() called")
    }
    else {
        initialize()
    }
    
    // if logs are in "Debug" turn down to "Info" after an hour
    logL = logLevel.toInteger()
    if (logL == 2) runIn(3600,logsOff)
}

def refresh(){
    debuglog("refresh() called")

    if (device.currentValue("eventStreamStatus") == "Connected" ||
        device.currentValue("eventStreamStatus") == "Connecting") {
        // set up for get status
        Map params = [
            uri: "http://${settings.ipAddr}",
            path: "/status.json",
            contentType: "application/json",
            requestContentType: "application/json",
            timeout: 5
        ]

        try {
            infolog("get status.json")
            // get initial status
            asynchttpGet("httpGetCallback", params)
        }
        catch (Exception e) {
            log.error "initialize() Execption: ${e}"
        }
    }
}

def httpGetCallback(response, data) {
    if (response.getStatus() == 200) {
        parsejsonResponse(response.json)
    }
}

def httpPostCallback(response, data) {
    if (response.getStatus() == 200) {
    }
}

def eventStreamStatus(String message) {
    infolog("eventStreamStatus() ${message}")
    
    if (message.startsWith("START:")) {
        sendEvent(name:"networkStatus", value: "online")
        sendEvent(name: "eventStreamStatus", value: "Connected", descriptionText:"${device.displayName} eventStreamStatus is Connected")
        shouldReconnect = true;
    }
    else if (message.startsWith("STOP:")) {
        sendEvent(name:"networkStatus", value: "offline")
        sendEvent(name: "eventStreamStatus", value: "Disconnected", descriptionText:"${device.displayName} eventStreamStatus is Disconnected")
        
        // try to reconnect
        if (shouldReconnect == true) {
            infolog("eventStreamStatus() stream stopped, reconnect = true, will re-init driver")
            runIn(10, "initialize")
        }
    }
    else if (message.startsWith("ERROR:")) {
        sendEvent(name:"networkStatus", value: "offline")
    	sendEvent(name: "eventStreamStatus", value: "Disconnected", descriptionText:"${device.displayName} eventStreamStatus is Disconnected")
        if (message.contains("SocketTimeoutException")) {
            // try to reconnect
            infolog("eventStreamStatus() Error - will re-init driver")
            runIn(10, "initialize")
        }
    }
}

// data from eventStream
void parse(String response) {
    if (response.length() == 0) return;
    debuglog("parse(): ${response}")

    if (response.startsWith("{")) {
        Map result = parseJson(response)
    	parsejsonResponse(result)
    }
	/*
    else {
        debuglog("parse(): raw!")
        // get field type (event, data, id, retry)
        field = response.split(":", 2)[0]
        //
        switch (field) {
            case "data":
                Map result = parseJson(response.split(":", 2)[1])
                parsejsonResponse(result)
        }
    }
	*/
    
    // retry:
    // id:
    // event: message
    // data: { "upTime": 27355256, "freeHeap": 114096, "minHeap": 45084, "wifiRSSI": "-39 dBm, Channel 6" }
    // data: { "garageLightOn": false, "upTime": 28974782 }
}

// parse json map
def parsejsonResponse(Map jsonResponse) {
    //debuglog("parsejsonResponse() json: ${jsonResponse}")

    sendEvent(name:"networkStatus", value: "online")
        
    jsonResponse.each { key, value ->
        switch (key) {
            case "upTime":
            	debuglog("parsejsonResponse() json: $key:$value")
            	def upTime = getHumanTimeFormatFromMilliseconds(value.toString())
            	sendEvent(name: "upTime", value: "$upTime (days:hrs:min:sec)")
            	break;
            
            case "lastDoorUpdateAt":
            case "doorUpdateAt":
            	debuglog("parsejsonResponse() json: $key:$value")
            	def date = new Date(now() - value)
                def finalString = date?.format('MM/d/yyyy hh:mm a',location.timeZone)
            	sendEvent(name: "lastDoorActivity", value: finalString)
            	break;
            
            case "ttcActive":
                debuglog("parsejsonResponse() json: $key:$value")
            	if (value.toInteger() > 0) {
                    refreshNeeded = true
                	sendEvent(name: "door", value: "closing", isStateChange: true, descriptionText:"${device.displayName} Door Status is $value")
                }
            	else {
                    if (refreshNeeded == true) {
                        refreshNeeded = false
                        
                        log.debug("i got here")
                        
                        runIn(5, "refresh")
                    }
                }
                break;
            
            case "garageDoorState":
                debuglog("parsejsonResponse() json: $key:$value")
            	// must be lower case
                sendEvent(name: "door", value: value.toLowerCase(), isStateChange: true, descriptionText:"${device.displayName} Door Status is $value")
                break;
            
            case "garageLightOn":
                debuglog("parsejsonResponse() json: $key:$value")
                sendEvent(name:"light", value: (value ? "on" : "off"), descriptionText: "${device.displayName} Light Status is $value")
            	updateComponentLight(value)
                break;
            
            case "garageLockState":
                debuglog("parsejsonResponse() json: $key:$value")
            	sendEvent(name:"remotes", value: value.toLowerCase(), descriptionText: "${device.displayName} Remote Status is $value")
                break;
                
            case "garageObstructed":
                debuglog("parsejsonResponse() json: $key:$value")
                sendEvent(name:"obstruction", value: value, descriptionText: "${device.displayName} Obstruction Sensor Status is $value")
                break;
        }
    }
    
}

def open() {
    debuglog("DOOR OPEN requested")
    
    sendCommand("garageDoorState", "1")
}

void close() {
    debuglog("DOOR CLOSE requested")

    sendCommand("garageDoorState", "0")
}

def LightOn() {
    infolog("LIGHT ON requested")

    sendCommand("garageLightOn", "1")
}

def LightOff() {
    debuglog("LIGHT OFF requested")

    sendCommand("garageLightOn", "0")
}

def RemotesEnabled() {
    debuglog("REMOTES ENABLED requested")
    
    sendCommand("garageLockState", "0")
}

// child component API
def componentRefresh(cd) {
}

def componentOff(cd) { 
	LightOff()
}
def componentOn(cd) { 
	LightOn()
}

def updateComponentLight(boolean value)
{
    childNetworkId = "${device.deviceNetworkId}-light"
    
	getChildDevice(childNetworkId)?.parse([[name:"switch", value: (value ? "on" : "off"), descriptionText:"GDO light was turned " + (value ? "on" : "off")]])
}

def RemotesDisabled() {
    debuglog("REMOTES DISABLED requested")
    
    sendCommand("garageLockState", "1")
}

def sendCommand(String key, String value) {
    debuglog("sendCommand($key, $value)")
    
    // check if event stream is connected... if disconnect do init
    if (device.currentValue("eventStreamStatus") == "Disconnected") {
        infolog("sendCommand() event stream is disconnected")
        initialize()

        //runin(4, sendCommand, [data: [key, value]])
    }
    
    debuglog("sendCommand() eventStreamStatus: ${device.currentValue("eventStreamStatus")}")
    
    //if (device.currentValue("eventStreamStatus") == "Connected") {
        try {
                Map params = [
                    uri: "http://${settings.ipAddr}",
                    path: "/setgdo",
                    query: ["$key" : "$value"],
                    timeout: 15
                ]

                asynchttpPost("httpPostCallback", params)
           } catch (Exception e) {
            debuglog("Exception in sendCommand() failed: ${e.message}")
        }
    //}
}


//
def String getHumanTimeFormatFromMilliseconds(String millisecondS) {
    String message = "";
    long milliseconds = Long.valueOf(millisecondS);

    int seconds = (int) ((milliseconds / 1000)) % 60;
    int minutes = (int) ((milliseconds / (1000 * 60))) % 60;
    int hours = (int) ((milliseconds / (1000 * 60 * 60))) % 24;
    int days = (int) (milliseconds / (1000 * 60 * 60 * 24));

    message = String.format("%d:%02d:%02d:%02d", days, hours, minutes, seconds);

    return message;
}

def logsOff() {
    log.warn "debug logging disabled"
    device.updateSetting("logLevel", [value: "1", type: "enum"])
}

def debuglog(statement)
{   
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 2)
    {
        log.debug("${device.label?device.label:device.name}: " + statement)
    }
}
def infolog(statement)
{       
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 1)
    {
        log.info("${device.label?device.label:device.name}: " + statement)
    }
}
def getLogLevels(){
    return [["0":"None"],["1":"Info"],["2":"Debug"]]
}
