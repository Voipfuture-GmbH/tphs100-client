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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.xml.sax.SAXException;

import de.codesourcery.jsonparser.Identifier;
import de.codesourcery.tplink.JenkinsClient.Job;
import de.codesourcery.tplink.JenkinsClient.JobStatus;
import de.codesourcery.tplink.TPLink.Command;
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
        final OptionSpecBuilder listAvailableCommands = parser.accepts("L","List all available commands");
        final ArgumentAcceptingOptionSpec<String> execCmd = parser.accepts("c","Execute command").withRequiredArg().describedAs("command name");
        parser.accepts("h").forHelp();
        parser.accepts( "help" , "displays this help").forHelp();
        
        final ArgumentAcceptingOptionSpec<String> userOpt = parser.accepts( "jenkinsuser" ,"Jenkins server IP/name").withRequiredArg();
        final ArgumentAcceptingOptionSpec<String> ignoredJobsOpt = parser.accepts( "ignoredjobs" ,"Comma-separated list with names of jobs that should be ignored").withRequiredArg();
        final ArgumentAcceptingOptionSpec<String> pwdOpt = parser.accepts( "jenkinspwd" , "Jenkins password").withRequiredArg();
        final ArgumentAcceptingOptionSpec<String> portOpt = parser.accepts( "jenkinsport" , "Jenkins port").withRequiredArg();
        final ArgumentAcceptingOptionSpec<String> schemeOpt = parser.accepts( "jenkinsscheme" , "Scheme (http/https) to use").withRequiredArg().defaultsTo("http");
        final ArgumentAcceptingOptionSpec<String> jenkinsHostOpt = parser.accepts( "jenkinshost" , "Jenkins username").requiredIf( userOpt , pwdOpt ).withRequiredArg(); 
        final OptionSpecBuilder verboseOpt = parser.accepts( "verbose","enable verbose output" );
        final OptionSpecBuilder versionOpt = parser.accepts( "version","Print application version" );
        final OptionSpecBuilder debugOpt = parser.accepts( "debug" , "enable debug output");
        final OptionSpecBuilder dryRunOpt = parser.accepts( "dry-run" , "Do not actually modify the plug's configuration/state");
        
        parser.nonOptions().describedAs("<plug IP/hostname> <on|off|info|jenkins>").ofType(String.class);
        
        final OptionSet options = parser.parse(args );

        if ( options.has( listAvailableCommands ) ) {
            Arrays.stream( TPLink.Command.values() ).map( cmd -> cmd.name() ).sorted().forEach( System.out::println );
            System.exit(0);
        }
        
        if ( options.has( versionOpt) ) 
        {
            System.out.println("Version "+TPLink.getVersion());
            if ( options.specs().size() == 1 ) {
                System.exit(0);
            }
        }        
        
        @SuppressWarnings("unchecked")
        int expectedSize = options.has( execCmd ) ? 1 : 2;
        final List<String> remaining = (List<String>) options.nonOptionArguments();
        if ( remaining.size() != expectedSize ) 
        {
            parser.printHelpOn( System.out );
            System.exit(1);
        }
        
        final boolean verbose = options.has("v") || options.has( verboseOpt );
        final boolean debug = options.has("d") || options.has( debugOpt );
        
        final InetAddress address = InetAddress.getByName( remaining.get(0) );
        final TPLink client = new TPLink( address );
        client.setVerbose( verbose );
        client.setDebug( debug );
        client.setDryRun( options.has( dryRunOpt ) );
        
        final String jenkinsHost = options.valueOf( jenkinsHostOpt );
        final String jenkinsUser = options.valueOf( userOpt );
        final String jenkinsScheme = options.valueOf( schemeOpt );
        final String jenkinsPassword = options.valueOf( pwdOpt );
        final String ignoredJobs = options.valueOf( ignoredJobsOpt );
        
        final Set<String> ignoredJobNames = new HashSet<>();
        if ( StringUtils.isNotBlank( ignoredJobs ) ) 
        {
            for ( String jobName : ignoredJobs.split(",") ) {
                if ( StringUtils.isBlank( jobName ) ) {
                    throw new IllegalArgumentException("--ignoredjobs argument must not contain blank job names");
                }
                ignoredJobNames.add( jobName.toLowerCase() );
            }
        }
        final Predicate<Job> isIgnored = job -> 
        {
            final String jobName = job.name.toLowerCase();
            boolean ignored = false;
            String reason = null;
            if ( jobName.startsWith("ignoreme") ) {
                ignored = true;
                reason = "IGNORED job because name starts with 'ignoreme'";
            } else {
                ignored = ignoredJobNames.contains( jobName );
                reason = "IGNORED job by configuration";
            }
            if ( verbose && ignored ) {
                System.out.println( reason+": "+job.name);
            }
            return ignored;
        };
        
        final int jenkinsPort = options.has( portOpt ) ? Integer.parseInt( options.valueOf( portOpt ) ) : -1; 
        
        if ( options.has( execCmd ) ) 
        {
            final String cmdName = options.valueOf( execCmd );
            final Function<Identifier, String> callback = identifier -> 
            {
                String result;
                try {
                    result = readUserInput( "Please enter a value for '"+identifier+"' : ");
                    return result;
                } 
                catch (IOException e) 
                {
                    e.printStackTrace();
                }
                System.exit(1);
                return null; // never reached,make compiler happy
            };
            client.sendCmd( Command.valueOf( cmdName ) , callback );
            System.exit(0);
        }
        
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
                jenkins.setDebug( debug );
                jenkins.setVerbose( verbose  );
                jenkins.setScheme( jenkinsScheme );

                final List<Job> projects = jenkins.getJobs();
                projects.removeIf( isIgnored );
                final boolean lightOn = projects.stream().map( j -> j.status).anyMatch( JobStatus::isFailure );
                if ( client.isVerbose() ) {
                    if ( lightOn ) {
                        System.out.println("The following projects failed to build:");
                        projects.stream().filter( p -> p.status.isFailure() ).forEach( p -> System.out.println( p.name ) );
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
            default:
                parser.printHelpOn( System.out );
                System.exit(1);
        }        
    }
    
    private static String readUserInput(String prompt) throws IOException 
    {
        String line = null;
        if ( System.console() != null ) {
            line = System.console().readLine( prompt );
        } else {
            System.out.flush();
            System.out.println( prompt );
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            line = r.readLine();
        }
        return line;
    }
}