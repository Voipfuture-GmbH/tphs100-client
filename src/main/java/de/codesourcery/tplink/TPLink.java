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
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Properties;
import java.util.function.Function;

import de.codesourcery.jsonparser.Identifier;
import de.codesourcery.jsonparser.Parser;
import de.codesourcery.jsonparser.ast.ASTNode;
import de.codesourcery.jsonparser.util.ASTPrinter;

/**
 * Very crude client to talk to TP-Link HS100/HS110 Wifi plugs.
 * 
 * @author tobias.gierke@code-sourcery.de
 */
public class TPLink
{
    private static final int IV = 171;

    private InetAddress destination;
    
    private boolean debug;
    private boolean verbose;
    private boolean dryRun;
    
    public static enum Command 
    {
        // System commands
        GET_SYSTEM_INFO("{\"system\":{\"get_sysinfo\":null}}",false),
        REBOOT("{\"system\":{\"reboot\":{\"delay\":1}}}"),
        FACTORY_RESET("{\"system\":{\"reset\":{\"delay\":1}}}"),
        PLUG_ON("{\"system\":{\"set_relay_state\":{\"state\":1}}}"),
        PLUG_OFF("{\"system\":{\"set_relay_state\":{\"state\":0}}}"),
        LED_ON("{\"system\":{\"set_led_off\":{\"off\":0}}}"),
        LED_OFF("{\"system\":{\"set_led_off\":{\"off\":1}}}"),
        SET_DEVICE_ALIAS("{\"system\":{\"set_dev_alias\":{\"alias\":\"${deviceAlias}\"}}}"), // "supercool plug'"
        SET_MAC_ADDRESS("{\"system\":{\"set_mac_addr\":{\"mac\":\"${macAddress}\"}}}"), // "50-C7-BF-01-02-03"
        SET_DEVICE_IF("{\"system\":{\"set_device_id\":{\"deviceId\":\"${deviceId}\"}}}"), // "0123456789ABCDEF0123456789ABCDEF01234567"
        SET_HARDWARE_ID("{\"system\":{\"set_hw_id\":{\"hwId\":\"${hardwareId}\"}}}"), // 0123456789ABCDEF0123456789ABCDEF
        SET_LOCATION("{\"system\":{\"set_dev_location\":{\"longitude\":${longitude},\"latitude\":${latitude}}}}"), // 50.9412784
        CHECK_BOOTLOADER("{\"system\":{\"test_check_uboot\":null}}",false),
        GET_DEVICE_ICON("{\"system\":{\"get_dev_icon\":null}}",false),
        SET_DEVICE_ICON("{\"system\":{\"set_dev_icon\":{\"icon\":\"xxxx\",\"hash\":\"ABCD\"}}}"),
        // Set Test Mode (command only accepted coming from IP 192.168.1.100)
        SET_TEST_MODE("{\"system\":{\"set_test_mode\":{\"enable\":1}}}"),
        DOWNLOAD_FIRMWARE("{\"system\":{\"download_firmware\":{\"url\":\"${url}\"}}}"), // http://....\
        GET_FIRMWARE_DOWNLOAD_STATE("{\"system\":{\"get_download_state\":{}}}"),
        FLASH_FIRMWARE("{\"system\":{\"flash_firmware\":{}}}"),
        CHECK_CONFIG("{\"system\":{\"check_new_config\":null}}"),
        // WLAN commands
        SCAN_APS("{\"netif\":{\"get_scaninfo\":{\"refresh\":1}}}"),
        CONNECT_TO_AP("{\"netif\":{\"set_stainfo\":{\"ssid\":\"${ssid}\",\"password\":\"${password}\",\"key_type\":3}}}"),
        // Cloud commands
        GET_CLOUD_INFO("{\"cnCloud\":{\"get_info\":null}}",false),
        GET_FIRMWARE_LIST("{\"cnCloud\":{\"get_intl_fw_list\":{}}}",false),
        SET_CLOUD_SERVER_URL("{\"cnCloud\":{\"set_server_url\":{\"server\":\"${cloudServerName}\"}}}"), // devs.tplinkcloud.com
        CONNECT_TO_CLOUD_SERVER("{\"cnCloud\":{\"bind\":{\"username\":\"${email}\", \"password\":\"${password}\"}}}"),
        UNREGISTER_FROM_CLOUD("{\"cnCloud\":{\"unbind\":null}}"),
        // Time commands
        GET_TIME("{\"time\":{\"get_time\":null}}",false),
        GET_TIMEZONE("{\"time\":{\"get_timezone\":null}}",false),
        SET_TIMEZONE("{\"time\":{\"set_timezone\":{\"year\":2016,\"month\":1,\"mday\":1,\"hour\":10,\"min\":10,\"sec\":10,\"index\":42}}}"),
        // Emeter commands
        GET_CURRENT_AND_VOLATAGE("{\"emeter\":{\"get_realtime\":{}}}",false),
        GET_VGAIN_AND_IGAIN("{\"emeter\":{\"get_vgain_igain\":{}}}",false),
        SET_VGAIN_AND_IGAIN("{\"emeter\":{\"set_vgain_igain\":{\"vgain\":13462,\"igain\":16835}}}"),
        CALIBRATE_EMETER("{\"emeter\":{\"start_calibration\":{\"vtarget\":13462,\"itarget\":16835}}}"),
        GET_EMETER_DAILY("{\"emeter\":{\"get_daystat\":{\"month\":1,\"year\":2016}}}",false),
        GET_EMETER_MONTHLY("{\"emeter\":{\"get_daystat\":{\"month\":1,\"year\":2016}}}",false),
        GET_EMETER_YEARLY("{\"emeter\":{\"get_monthstat\":{\"year\":2016}}}",false),
        RESET_EMETER_STATS("{\"emeter\":{\"erase_emeter_stat\":null}}"),
        // Schedule commands
        GET_NEXT_SCHEDULE_ACTION("{\"schedule\":{\"get_next_action\":null}}",false),
        GET_SCHEDULE_RULES("{\"schedule\":{\"get_rules\":null}}",false),
        ADD_SCHEDULE_RULE("{\"schedule\":{\"add_rule\":{\"stime_opt\":0,\"wday\":[1,0,0,1,1,0,0],\"smin\":1014,\"enable\":1,\"repeat\":1,\"etime_opt\":-1,\"name\":\"lights on\",\"eact\":-1,\"month\":0,\"sact\":1,\"year\":0,\"longitude\":0,\"day\":0,\"force\":0,\"latitude\":0,\"emin\":0},\"set_overall_enable\":{\"enable\":1}}}"),
        EDIT_SCHEDULE_RULE("{\"schedule\":{\"edit_rule\":{\"stime_opt\":0,\"wday\":[1,0,0,1,1,0,0],\"smin\":1014,\"enable\":1,\"repeat\":1,\"etime_opt\":-1,\"id\":\"4B44932DFC09780B554A740BC1798CBC\",\"name\":\"lights on\",\"eact\":-1,\"month\":0,\"sact\":1,\"year\":0,\"longitude\":0,\"day\":0,\"force\":0,\"latitude\":0,\"emin\":0}}}"),
        DELETE_SCHEDULE_RULE("{\"schedule\":{\"delete_rule\":{\"id\":\"4B44932DFC09780B554A740BC1798CBC\"}}}"),
        DELETE_ALL_SCHEDULE_RULES_AND_STATISTICS("{\"schedule\":{\"delete_all_rules\":null,\"erase_runtime_stat\":null}}"),
        // Countdown rule commands
        GET_COUNTDOWN_RULE("{\"count_down\":{\"get_rules\":null}}",false),
        ADD_COUNTDOWN_RULE("{\"count_down\":{\"add_rule\":{\"enable\":1,\"delay\":1800,\"act\":1,\"name\":\"turn on\"}}}"),
        EDIT_COUNTDOWN_RULE("{\"count_down\":{\"edit_rule\":{\"enable\":1,\"id\":\"7C90311A1CD3227F25C6001D88F7FC13\",\"delay\":1800,\"act\":1,\"name\":\"turn on\"}}}"),
        DELETE_COUNTDOWN_RULE("{\"count_down\":{\"delete_rule\":{\"id\":\"7C90311A1CD3227F25C6001D88F7FC13\"}}}"),
        DELETE_ALL_COUNTDOWN_RULES("{\"count_down\":{\"delete_all_rules\":null}}"),
        // Anti-theft commands 
        // (period of time during which device will be randomly turned on and off to deter thieves) 
        GET_ANTITHEFT_RULES("{\"anti_theft\":{\"get_rules\":null}}",false),
        ADD_ANTITHEFT_RULE("{\"anti_theft\":{\"add_rule\":{\"stime_opt\":0,\"wday\":[0,0,0,1,0,1,0],\"smin\":987,\"enable\":1,\"frequency\":5,\"repeat\":1,\"etime_opt\":0,\"duration\":2,\"name\":\"test\",\"lastfor\":1,\"month\":0,\"year\":0,\"longitude\":0,\"day\":0,\"latitude\":0,\"force\":0,\"emin\":1047},\"set_overall_enable\":1}}"), 
        EDIT_ANTITHEFT_RULE("{\"anti_theft\":{\"edit_rule\":{\"stime_opt\":0,\"wday\":[0,0,0,1,0,1,0],\"smin\":987,\"enable\":1,\"frequency\":5,\"repeat\":1,\"etime_opt\":0,\"id\":\"E36B1F4466B135C1FD481F0B4BFC9C30\",\"duration\":2,\"name\":\"test\",\"lastfor\":1,\"month\":0,\"year\":0,\"longitude\":0,\"day\":0,\"latitude\":0,\"force\":0,\"emin\":1047},\"set_overall_enable\":1}}"),
        DELETE_ANTITHEFT_RULE("{\"anti_theft\":{\"delete_rule\":{\"id\":\"E36B1F4466B135C1FD481F0B4BFC9C30\"}}}"),
        DELETE_ALL_ANTITHEFT_RULES("{\"anti_theft\":{\"delete_all_rules\":null}}");
        
        public final String json;
        public final boolean altersDeviceState;
        
        private Command(String json) {
            this(json,true);
        }
        
        private Command(String json,boolean changesState) {
            this.json = json;
            this.altersDeviceState = changesState;
            new Parser().parse( json ); // throws exception on invalid JSON
        }
    }

    public TPLink(InetAddress destination) {
        this.destination = destination;
    }
    
    public String getSystemInfo() throws IOException {
        return sendCmd( Command.GET_SYSTEM_INFO );
    }
    
    public void on() throws IOException {
        sendCmd( Command.PLUG_ON );
        // TODO: Doesn't check device response for errors
    }
    
    public void off() throws IOException {
        sendCmd( Command.PLUG_OFF );
        // TODO: Doesn't check device response for errors
    }    
    
    private void verbose(String msg) {
        if ( verbose ) {
            System.out.println( msg );
        }
    }
    
    private void debug(String msg) {
        if ( debug ) {
            System.out.println("DEBUG: "+msg);
        }
    }
    
    public String sendCmd(Command cmd) throws IOException 
    {
        return sendCmd(cmd,id -> null);
    }
    
    private String resolvePlaceholders(String json,Function<Identifier,String> placeholderResolver) 
    {
        final ASTNode ast = new Parser().parse( json );
        final ASTPrinter printer = new ASTPrinter();
        printer.setPrettyPrint( false );
        final Function<Identifier,String> wrapper = id -> 
        {
            final String result = placeholderResolver.apply( id );
            if ( result == null ) {
                throw new RuntimeException("Missing placeholder value for '"+id.name+"'");
            }
            return result;
        };
        return printer.print( ast , wrapper );
    }
    
    public String sendCmd(Command cmd,Function<Identifier,String> placeholderResolver) throws IOException 
    {
        final String dryRunPrefix = isDryRun() ? "DRY-RUN: " : "";
        final String json = resolvePlaceholders( cmd.json , placeholderResolver );
        verbose(dryRunPrefix+"Sending command "+cmd.name());
        if ( isDryRun() && cmd.altersDeviceState ) {
            debug("Sending command "+cmd+" to "+destination+" , port 9999 TCP" );
            debug( json );
            return ""; // TODO: Would need to fake response here
        }
        return sendCmd( json );
    }
    
    private String sendCmd(String cmd) throws IOException 
    {
        debug("Sending command "+cmd+" to "+destination+" , port 9999 TCP" );
        
        try ( Socket clientSocket = new Socket( destination , 9999) )
        {
            // send command
            final DataOutputStream out = new DataOutputStream( clientSocket.getOutputStream() );

            final byte[] data = encrypt( cmd );
            out.write( data , 0 , data.length );
            
            // receive response
            final DataInputStream in = new DataInputStream( clientSocket.getInputStream() );
            final ByteArrayOutputStream recBuffer = new ByteArrayOutputStream();

            while ( true ) 
            {
                final int recv = in.read();
                if ( recv == -1 ) {
                    break;
                }
                recBuffer.write( recv );
            }
            clientSocket.close();
            final String result = decrypt( recBuffer.toByteArray() );
            debug("received: "+result);
            return result;
        }
    }

    private static byte[] encrypt(String input) throws IOException 
    {
        int key = IV;

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write( new byte[] { 0, 0, 0, 0 } );

        for ( char c : input.toCharArray() ) 
        {
            int b = (key ^ (byte) c );
            key = b;
            out.write( b );
        }
        return out.toByteArray();
    }

    private static String decrypt(byte[] input) 
    {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int key = IV;
        for ( byte curByte : Arrays.copyOfRange(input,4,input.length) ) 
        {
            int b = (key ^ curByte );
            key = curByte;
            output.write( b );
        }
        return output.toString();
    }
    
    public void setVerbose(boolean verbose)
    {
        this.verbose = verbose;
    }
    
    public boolean isVerbose()
    {
        return verbose;
    }
    
    public void setDebug(boolean debug)
    {
        this.debug = debug;
    }
    
    public boolean isDebug()
    {
        return debug;
    }
    
    public static String getVersion() 
    {
        try ( final InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/maven/de.codesourcery.tplink/tphs100-client/pom.properties") ) 
        {
            if ( in != null ) 
            {
                final Properties props = new Properties();
                props.load( in );
                final String version = props.getProperty("version");
                if ( version != null && version.trim().length() > 0 ) {
                    return version.trim();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "<failed to determine version>";
    }
    
    public void setDryRun(boolean dryRun)
    {
        this.dryRun = dryRun;
    }
    
    public boolean isDryRun()
    {
        return dryRun;
    }    
}