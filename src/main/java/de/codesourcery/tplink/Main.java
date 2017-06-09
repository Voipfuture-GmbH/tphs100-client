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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class Main
{
    private static void printHelp() 
    {
        System.out.println("\n\nnUSAGE: [-v|--verbose] [-d|--debug] <IP address> <info|on|off>\n\n");
        System.exit(1);
    }
    
    public static void main(String[] cmdLine) throws IOException, InterruptedException
    {
        final List<String> args = new ArrayList<>( Arrays.asList( cmdLine ) );
        final boolean verbose = args.removeIf( (Predicate<String>) s -> s.equals("-v") || s.equals("--verbose" ) );
        final boolean debug = args.removeIf( (Predicate<String>) s -> s.equals("-d") || s.equals("--debug" ) );
        
        if ( args.size() != 2 ) 
        {
            printHelp();
        }
        
        final InetAddress address = InetAddress.getByName( args.get(0) );
        final TPLink client = new TPLink( address );
        client.setVerbose( verbose );
        client.setDebug( debug );
        switch( args.get(1).toLowerCase() ) 
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
            case "-h":
            case "--help":
            case "-help":
            default:
                printHelp();
        }
    }
}