/**
 * Copyright 2015 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.tplink;

import java.io.IOException;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.List;
import java.util.function.Predicate;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.xml.sax.SAXException;

import de.codesourcery.tplink.JenkinsClient.Job;
import de.codesourcery.tplink.JenkinsClient.JobStatus;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpecBuilder;

/**
 * Command-line interface.
 * 
 * <p>Use '-h' or '--help' to see the available options.
 * 
 * </p>
 * 
 * @author tobias.gierke@voipfuture.com
 */
public class Main
{
    public static void main(String[] args) throws IOException, InterruptedException, ParserConfigurationException, SAXException, ParseException
    {
        final OptionParser parser = new OptionParser();
        parser.accepts("d");
        parser.accepts("v");
        parser.accepts("h").forHelp();
        parser.accepts( "help" , "displays this help").forHelp();
        
        final ArgumentAcceptingOptionSpec<String> userOpt = parser.accepts( "jenkinsuser" ,"Jenkins server IP/name").withRequiredArg();
        final ArgumentAcceptingOptionSpec<String> pwdOpt = parser.accepts( "jenkinspwd" , "Jenkins password").withRequiredArg();
        final ArgumentAcceptingOptionSpec<String> portOpt = parser.accepts( "jenkinsport" , "Jenkins port").withRequiredArg();
        final ArgumentAcceptingOptionSpec<String> schemeOpt = parser.accepts( "jenkinsscheme" , "Scheme (http/https) to use").withRequiredArg().defaultsTo("http");
        final ArgumentAcceptingOptionSpec<String> jenkinsHostOpt = parser.accepts( "jenkinshost" , "Jenkins username").requiredIf( userOpt , pwdOpt ).withRequiredArg(); 
        final OptionSpecBuilder verboseOpt = parser.accepts( "verbose","enable verbose output" );
        final OptionSpecBuilder debugOpt = parser.accepts( "debug" , "enable debug output");
        
        parser.nonOptions().describedAs("<plug IP/hostname> <on|off|info|jenkins>").ofType(String.class);
        
        final OptionSet options = parser.parse( args );
        
        
        final List<String> remaining = (List<String>) options.nonOptionArguments();
        if ( remaining.size() != 2 ) {
            parser.printHelpOn( System.out );
            System.exit(1);
        }
        
        final InetAddress address = InetAddress.getByName( remaining.get(0) );
        final TPLink client = new TPLink( address );
        client.setVerbose( options.has("v") || options.has( verboseOpt ) );
        client.setDebug( options.has("d") || options.has( debugOpt ) );

        final String jenkinsHost = options.valueOf( jenkinsHostOpt );
        final String jenkinsUser = options.valueOf( userOpt );
        final String jenkinsScheme = options.valueOf( schemeOpt );
        final String jenkinsPassword = options.valueOf( pwdOpt );
        
        
        final int jenkinsPort = options.has( "jenkinsport" ) ? Integer.parseInt( options.valueOf( portOpt ) ) : -1; 
        
        switch( remaining.get(1) ) 
        {
            case "info":
                System.out.println( client.getSystemInfo() );
                break;
            case "on":
                client.on();
                break;
            case "off":
                client.off();
                break;
            case "jenkins":
                final JenkinsClient jenkins = new JenkinsClient( jenkinsHost );
                if ( StringUtils.isNotBlank( jenkinsUser ) ) {
                    jenkins.setUsername( jenkinsUser );
                    jenkins.setPassword( jenkinsPassword );
                }
                if ( jenkinsPort != -1 ) {
                    jenkins.setPort( jenkinsPort );
                }
                jenkins.setScheme( jenkinsScheme );

                final Predicate<Job> pred = proj -> proj.status == JobStatus.FAILURE || proj.status == JobStatus.SUCCESS ;
                final List<Job> projects = jenkins.getJobs();
                final boolean lightOn = projects.stream().filter( pred ).anyMatch( p -> p.status != JobStatus.SUCCESS );
                if ( client.isVerbose() ) {
                    if ( lightOn ) {
                        System.out.println("The following projects failed to build:");
                        projects.stream().filter( p -> p.status == JobStatus.FAILURE ).forEach( p -> System.out.println( p.name ) );
                    } else {
                        System.out.println("No failed builds.");
                    }
                }
                if ( lightOn ) {
                    client.on();
                } else {
                    client.off();
                }
                break;
            case "-h":
            case "--help":
            case "-help":
            default:
                parser.printHelpOn( System.out );
                System.exit(1);
        }        
    }
}