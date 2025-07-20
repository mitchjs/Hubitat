/*
 * RatGDO (Homekit FW), using HTTP access only
 * By: Mitch Solomon@rush2112.net
 * REQUIRES HOMEKIT FW: https://github.com/ratgdo/homekit-ratgdo
 */

import groovy.transform.Field

@Field static def shouldReconnect = false

metadata {
    definition(name: "Http ratGdo", namespace: "MJS Gadgets", author: "MitchJS", importUrl: "") {
        capability "Garage Door Control"
		capability "Light"
        capability "Lock"  
        capability "Refresh"
                
        capability "Initialize"
        //capability "Configuration"
        
        //command "testCode"
        
        //attribute "door", "enum", ["unknown", "open", "closing", "closed", "opening"]
        attribute "light", "enum", ["on","off"]
        attribute "lock", "enum", ["locked","unlocked"]
        attribute "availability", "enum", ["offline","online"]
        attribute "obstruction", "enum", ["obstructed","clear"]
        
        attribute "lastDoorActivity", "string"
        attribute "eventStreamStatus", "string"
    }
}

preferences {
	input name: "ipAddr", type: "text", title: "IP Address of RatGDO (homekit fw)", required: true          
    input name: "logLevel",title: "Logging Level", multiple: false, required: true, type: "enum", options: getLogLevels(), submitOnChange : false, defaultValue : "1"
}


def testCode() {
    def date = new Date()
	def finalString = date?.format('MM/d/yyyy hh:mm a',location.timeZone)
	sendEvent(name: "lastDoorActivity", value: finalString, display: false , displayed: false)
}

def initialize() {
    infolog("initialize() called")
    
    // make sure we got an IP
    if (!settings.ipAddr) {
        infolog("No IP Address in prefs")
        return
    }
    
    // temporally prevent reconnection
    if (device.currentValue("eventStreamStatus") == "Connected") {
        
        shouldReconnect = false
    }
      
    sendEvent(name: "eventStreamStatus", value: "Connecting", display: true, descriptionText:"${device.displayName} eventStreamStatus is Connecting")

    infolog("initialize() called, request subscribe to SSE")
        
    // set up for subscribe
    params = [
        uri: "http://${settings.ipAddr}",
        contentType: "text/html",
        path: "/rest/events/subscribe",
        query: [id : "${device.id}", "heartbeat" : "30"],
        body: "",
        timeout: 5
        //headers: headers
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
    }
    
    // if logs are in "Debug" turn down to "Info" after an hour
    logL = logLevel.toInteger()
    if (logL == 2) runIn(3600,logsOff)
}

def updated() {
    infolog "updated..."
    
    if (device.currentValue("eventStreamStatus") == "Connected" ||
        device.currentValue("eventStreamStatus") == "Connecting") {
        	sendEvent(name: "eventStreamStatus", value: "Disconnecting", display: true, descriptionText:"${device.displayName} eventStreamStatus is Disconnecting")
    		interfaces.eventStream.close()
    }
    else {
        initialize()
    }
    
    // if logs are in "Debug" turn down to "Info" after an hour
    logL = logLevel.toInteger()
    if (logL == 2) runIn(3600,logsOff)
}

def refresh(){
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
    debuglog("eventStreamStatus() ${message}")
    
    if (message.startsWith("START:")) {
        sendEvent(name: "eventStreamStatus", value: "Connected", display: true, descriptionText:"${device.displayName} eventStreamStatus is Connected")
        shouldReconnect = true;
    }
    else if (message.startsWith("STOP:")) {
        sendEvent(name: "eventStreamStatus", value: "Disconnected", display: true, descriptionText:"${device.displayName} eventStreamStatus is Disconnected")
        
        // try to reconnect
        if (shouldReconnect == true) {
            debuglog("eventStreamStatus() will re-init")
            runIn(5, "initialize")
        }
    }
    else if (message.startsWith("ERROR:")) {
    }
}

// data from eventStream
void parse(String response) {
    debuglog("parse() ${response}")
    Map result = parseJson(response)
    parsejsonResponse(result)
}

// parse json map
def parsejsonResponse(Map jsonResponse) {
    
    //log.debug "parsejsonResponse() json: ${jsonResponse}"
        
    jsonResponse.each { key, value ->
        switch (key) {
            case "garageDoorState":
                debuglog("parsejsonResponse() json: $key:$value")
            
            	if (device.currentValue("door") != value.toLowerCase()) {
                    def date = new Date()
                    def finalString = date?.format('MM/d/yyyy hh:mm a',location.timeZone)
					sendEvent(name: "lastDoorActivity", value: finalString, display: false , displayed: false)
                }
            
                sendEvent(name: "door", value: value.toLowerCase(), display: true, isStateChange: true, descriptionText:"${device.displayName} Door Status is $value")
                break;
            
            case "garageLightOn":
                debuglog("parsejsonResponse() json: $key:$value")
                sendEvent(name:"light", value: (value ? "on" : "off"), display: true, descriptionText: "${device.displayName} Light Status is $value")
                break;
            
            case "garageLockState":
                debuglog("parsejsonResponse() json: $key:$value")
                sendEvent(name:"lock", value: value, display: true, descriptionText: "${device.displayName} Lock Status is $value")
                break;
                
            case "garageObstructed":
                debuglog("parsejsonResponse() json: $key:$value")
                sendEvent(name:"obstruction", value: value, display: true, descriptionText: "${device.displayName} Obstruction Sensor Status is $value")
                break;
        }
    }
    
}

def open() {
    infolog("DOOR OPEN requested")
    
    sendCommand("garageDoorState", "1")
}

void close() {
    infolog("DOOR CLOSE requested")

    sendCommand("garageDoorState", "0")
}

def on() {
    infolog("LIGHT ON requested")

    sendCommand("garageLightOn", "1")
}

def off() {
    infolog("LIGHT OFF requested")

    sendCommand("garageLightOn", "0")
}

def lock() {
    infolog("LOCK SECURED requested")
    sendCommand("garageLockState", "1")
}

def unlock() {
    infolog("LOCK UNSECURED requested")
    
    sendCommand("garageLockState", "0")
}

def sendCommand(String key, String value) {
    infolog("sendCommand($key, $value)")
    
    
    // check if event stream is connected... if disconnect do init
    if (device.currentValue("eventStreamStatus") == "Disconnected") {
        infolog("sendCommand() event stream is disconnected")
        initialize()

        //runin(4, sendCommand, [data: [key, value]])
    }
    
    debuglog("sendCommand() ${device.currentValue("eventStreamStatus")}")
    
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