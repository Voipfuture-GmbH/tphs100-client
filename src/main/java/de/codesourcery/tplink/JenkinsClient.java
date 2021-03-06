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

import static org.apache.commons.lang3.StringUtils.EMPTY;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.SAXException;

/**
 * Very crude Apache Jenkins client that uses the JSON API to retrieve
 * a list of all projects and their build status.
 *
 * @author tobias.gierke@voipfuture.com
 */
@SuppressWarnings("deprecation")
public class JenkinsClient implements AutoCloseable
{
    private String username;
    private String password;
    private int port = 80;
    private String scheme = "http";
    private String host;

    private CloseableHttpClient httpClient;
    private BasicHttpContext clientContext;

    private boolean debug;
    private boolean verbose;

    /**
     * Jenkins job status.
     *
     * @author tobias.gierke@voipfuture.com
     */
    public static enum JobStatus
    {
        FAILURE("red") {
            @Override public boolean isFailure() { return true; }
        },
        FAILURE_BUILDING("red_anime") {
            @Override public boolean isFailure() { return true; }
        },
        UNSTABLE("yellow"),
        UNSTABLE_BUILDING("yellow_anime"),
        SUCCESS("blue"),
        SUCCESS_BUILDING("blue_anime"),
        GREY("grey"),
        GREY_ANIME("grey_anime"),
        DISABLED("disabled"),
        DISABLED_PENDING("disabled_anime"),
        ABORTED("aborted"),
        ABORTED_PENDING("aborted_anime"),
        NOTBUILT("notbuilt"),
        NOTBUILT_PENDING("nobuilt_anime");

        private final String jenkinsText;

        private JobStatus(String text) {
            this.jenkinsText = text;
        }

        public boolean isFailure() {
            return false;
        }

        public boolean isAborted() {
            return this == ABORTED_PENDING || this == ABORTED;
        }

        public static JobStatus fromString(String s)
        {
            if ( s == null || s.trim().length() == 0 ) {
                throw new IllegalArgumentException("Project status must not be NULL/blank");
            }
            return Arrays.stream( values() ).filter( t -> s.equals( t.jenkinsText ) ).findFirst().orElseThrow( () -> new IllegalArgumentException("Unknown job color '"+s+"'"));
        }
    }

    /**
     * A Jenkins job.
     *
     * @author tobias.gierke@voipfuture.com
     */
    public static class Job
    {

        public String name;
        public JobStatus status;

        public Job(String name, JobStatus status)
        {
            this.name = name;
            this.status = status;
        }

        public boolean hasStatus(JobStatus s) {
            return s.equals( this.status );
        }

        @Override
        public String toString()
        {
            return name+"[ "+status+" ]";
        }
    }

    public JenkinsClient(String serverName)
    {
        this.host = serverName;
    }

    private void verbose(String s) {
        if ( verbose ) {
            System.out.println(s);
        }
    }

    private void debug (String s) {
        if ( debug ) {
            System.out.println(s);
        }
    }

    /**
     * Returns all Jenkins jobs whose status is accessible to the current user
     * without the jenkins jobs with the <code>ignoreme</code> prefix.
     *
     * @return
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    public List<Job> getJobs() throws IOException, ParserConfigurationException, SAXException
    {
        try ( InputStream in = scrape() )
        {
            String jsonString;
            try (BufferedReader buffer = new BufferedReader(new InputStreamReader(in)))
            {
                jsonString = buffer.lines().collect(Collectors.joining("\n"));
            }

            final List<Job> result = new ArrayList<>();
            final JSONObject obj = new JSONObject( jsonString );
            final JSONArray jobs = obj.getJSONArray("jobs");
            for ( int i = 0 ; i < jobs.length() ; i++ )
            {
                final JSONObject job = jobs.getJSONObject( i );
                final String jobName = job.getString( "name" );

                if( ! job.has("color") ) { // jobs of class 'org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject' do not have this attribute 
                    continue;
                }

                final String jobColor = job.getString( "color" );

                JobStatus jobstatus;
                try {
                    jobstatus = JobStatus.fromString( jobColor );
                }
                catch(RuntimeException e)
                {
                    System.err.println("Failed to parse status '"+jobColor+" for job '"+jobName+"'");
                    throw e;
                }

                // don't ignore the failed jobs if they were aborted
                if (jobstatus.isAborted() && wasFailedJob(job)) {
                    jobstatus = JobStatus.FAILURE;
                }

                final Job toAdd = new Job( jobName , jobstatus );
                if ( debug ) {
                    debug( toAdd.toString() );
                }
                result.add( toAdd );
            }
            verbose("Got "+result.size()+" jobs");
            return result;
        }
    }

    /**
     * Checks if the failed build is the most recent one compared to the successful build.
     * @param job
     * @return
     */
    private boolean wasFailedJob(JSONObject job) {
        final String jobName = job.getString("name");
        final String lastJob = doGetRequest("/job/" + jobName + "/api/json/");
        final JSONObject lastJobJSON = new JSONObject(lastJob);
        
        final JSONObject sucessfulBuild;
        if ( lastJobJSON.isNull("lastSuccessfulBuild") ) {
            sucessfulBuild = null;
        } else {
            sucessfulBuild = lastJobJSON.getJSONObject("lastSuccessfulBuild");
        }
        
        final JSONObject failedBuild; 
        if ( lastJobJSON.isNull( "lastFailedBuild" ) ) {
            failedBuild = null;
        } else {
            failedBuild = lastJobJSON.getJSONObject("lastFailedBuild");
        }
        
        final int lastSuccessfulBuildNumber = sucessfulBuild == null ? failedBuild == null ? 0 : failedBuild.getInt("number") : sucessfulBuild.getInt("number");
        final int lastFailedBuildNumber = failedBuild == null ? sucessfulBuild == null ? 0 : sucessfulBuild.getInt("number") : failedBuild.getInt("number");
        return lastFailedBuildNumber >= lastSuccessfulBuildNumber;
    }

    private String doGetRequest(String urlSuffix) {
        String result = EMPTY;
        try {
            final URI uri = URI.create(scheme + "://" + host + ":" + port + urlSuffix);

            verbose("URI: " + uri);

            final HttpGet httpGet = new HttpGet(uri);

            final HttpResponse response;
            if (isAuthEnabled()) {
                response = getClient().execute(getHost(), httpGet, clientContext);
            } else {
                response = getClient().execute(getHost(), httpGet);
            }
            result = EntityUtils.toString(response.getEntity());

            if (verbose) {
                System.out.println("GOT: " + result);
            }

            if (response.getStatusLine().getStatusCode() != 200) {
                throw new IOException("Received " + response.getStatusLine());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    private HttpHost getHost() throws UnknownHostException {
        return new HttpHost(InetAddress.getByName( this.host ), port , scheme);
    }

    private class PreemptiveAuthInterceptor implements HttpRequestInterceptor {

        public void process(final HttpRequest request, final HttpContext context) throws HttpException, IOException
        {
            final AuthState authState = (AuthState) context.getAttribute(ClientContext.TARGET_AUTH_STATE);
            if ( authState.getAuthScheme() == null ) {
                final AuthScheme authScheme = (AuthScheme) context.getAttribute("preemptive-auth");
                final BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(new AuthScope(getHost()), new UsernamePasswordCredentials(username, password));
                if (authScheme != null) {
                    authState.setAuthScheme(authScheme);
                    authState.setCredentials(new UsernamePasswordCredentials(username, password));
                }
            }

        }
    }

    private CloseableHttpClient getClient() throws UnknownHostException
    {
        if ( httpClient != null ) {
            return httpClient;
        }

        if ( isAuthEnabled() )
        {
            verbose("Connecting to "+host+" (auth_enabled: true)");
            httpClient = HttpClients.custom().addInterceptorFirst( new PreemptiveAuthInterceptor() ).build();
            clientContext = new BasicHttpContext();
            clientContext.setAttribute("preemptive-auth",new BasicScheme());
        } else {
            verbose("Connecting to "+host);
            httpClient = HttpClients.createMinimal();
        }
        return httpClient;
    }

    private boolean isAuthEnabled() {
        return username != null;
    }

    public InputStream scrape() throws IOException
    {
        final String content = doGetRequest("/api/json");
        return new ByteArrayInputStream( content.getBytes() );
    }

    /**
     * Enable/disable verbose output to stdout.
     *
     * @param verbose
     */
    public void setVerbose(boolean verbose)
    {
        this.verbose = verbose;
    }

    /**
     * Sets the username to use when authenticating against the Jenkins server.
     *
     * @param username user name, blank/NULL of no authentication should be used.
     */
    public void setUsername(String username)
    {
        this.username = username;
    }

    /**
     * Sets the password to be used when authenticating against the Jenkins server.
     *
     * @param password
     */
    public void setPassword(String password)
    {
        this.password = password;
    }

    public boolean isVerbose()
    {
        return verbose;
    }

    /**
     * Closes the underlying HTTP client connection.
     */
    @Override
    public void close() throws Exception
    {
        if ( httpClient != null )
        {
            final CloseableHttpClient tmp = httpClient;
            httpClient = null;
            tmp.close();
        }
    }

    public void setScheme(String scheme)
    {
        this.scheme = scheme;
    }

    public void setPort(int jenkinsPort)
    {
        if ( port < 1 || port > 65535 ) {
            throw new IllegalArgumentException("Port number must be > 0 && < 65536");
        }
        this.port =jenkinsPort;
    }

    public void setDebug(boolean debug)
    {
        this.debug = debug;
    }

    public boolean isDebug()
    {
        return debug;
    }
}
