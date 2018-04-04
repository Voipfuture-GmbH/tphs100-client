# What's this?

This is a tiny Java client that is able to query the job status from a Jenkins server and switch a TP-Link HS100/HS110 Wifi plug on or off according to the build status. If at least one build is in the 'failed' state, the plug will be switched on.
Note that it will skip the jenkins jobs which have the name with prefix 'ignoreme'.

# Building

To build you will need Maven 3.x and JDK 1.8

# Dependency
[jsonparser](https://github.com/toby1984/jsonparser)

Just run
```
mvn clean package
```
and you should find a self-executable JAR inside the target folder.

# Running

```
java -jar target/tphs100-client.jar [-d|--debug] [-v|--verbose] [--ignoredjobs <jobnames>] [--dry-run] [--version] [--jenkinshost <hostname>] [--jenkinsuser <username>] [--jenkinspwd <password>] <plug IP/hostname> <on|off|info|jenkins>
```
Available commands:
* on - switch plug in
* off - switch plug off
* info - query information from the plug
* jenkins - "Jenkins mode" , query jobs from jenkins server and switch plug accordingly

Available options:

Name           Description           
------
-d                                                   
--debug          enable debug output                 
--dry-run        Do not actually modify the plug's   
                   configuration/state               
-h                                                   
--help           displays this help    
--ignoredjobs    Comma-separated list of job names (case-
insensitive)
--jenkinshost    Jenkins username                    
--jenkinsport    Jenkins port                        
--jenkinspwd     Jenkins password                    
--jenkinsscheme  Scheme (http/https) to use (default:
                   http)                             
--jenkinsuser    Jenkins server IP/name              
-v                                                   
--verbose        enable verbose output               
--version        print application version 
