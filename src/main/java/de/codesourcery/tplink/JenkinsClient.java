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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Very crude Apache Jenkins client that uses the XML API to retrieve
 * a list of all projects and their build status.
 * 
 * @author tobias.gierke@voipfuture.com
 */
@SuppressWarnings("deprecation")
public class JenkinsClient implements AutoCloseable
{
    private final XPathExpression jobExpression;    
    private final XPathExpression jobColorExpression;    
    private final XPathExpression jobNameExpression;    

    private String username;
    private String password;
    private int port = 80;
    private String scheme = "http";
    private String host;

    private CloseableHttpClient httpClient;
    private BasicHttpContext clientContext;    

    private boolean verbose;

    private static final class DummyResolver implements EntityResolver {

        @Override
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException
        {
            final ByteArrayInputStream dummy = new ByteArrayInputStream(new byte[0]);
            return new InputSource(dummy);
        }
    }    

    /**
     * Jenkins job status.
     * 
     * @author tobias.gierke@voipfuture.com
     */
    public static enum JobStatus 
    { 
        FAILURE("red"),
        FAILURE_BUILDING("red_anime"),
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
        NOTBUILT("nobuilt"),
        NOTBUILT_PENDING("nobuilt_anime");

        private final String jenkinsText;

        private JobStatus(String text) {
            this.jenkinsText = text;
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

        final XPathFactory factory = XPathFactory.newInstance();
        final XPath xpath = factory.newXPath();

        try {
            jobExpression = xpath.compile("/hudson/job");
            jobNameExpression = xpath.compile("name");
            jobColorExpression = xpath.compile("color");
        } 
        catch (XPathExpressionException e) 
        {
            throw new RuntimeException(e);
        }
    }

    private void verbose(String s) {
        if ( verbose ) {
            System.out.println(s);
        }
    }

    /**
     * Returns all Jenkins jobs whose status is accessible to the current user.
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
            final Document doc = parseXML( in );

            final List<Job> result = new ArrayList<>();
            for ( Element tag : evaluate( jobExpression , doc ) ) 
            {
                final String jobName = getValue( jobNameExpression , tag );
                final String jobColor = getValue( jobColorExpression , tag );
                JobStatus jobstatus;
                try {
                    jobstatus = JobStatus.fromString( jobColor );
                } catch(RuntimeException e) 
                {
                    System.err.println("Failed to parse status '"+jobColor+" for job '"+jobName+"'");
                    throw e;
                }
                result.add( new Job( jobName , jobstatus ) );
            }
            verbose("Got "+result.size()+" jobs");
            return result;
        }
    }

    protected static Document parseXML(InputStream inputStream) throws ParserConfigurationException, SAXException, IOException
    {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        final DocumentBuilder builder = factory.newDocumentBuilder();

        // set fake EntityResolver , otherwise parsing is incredibly slow (~1 sec per file on my i7)
        // because the parser will download the DTD from the internets...
        builder.setEntityResolver( new DummyResolver() );
        return builder.parse( inputStream);
    }    

    private String getValue(XPathExpression expr, Node document) 
    {
        try {
            return (String) expr.evaluate(document,XPathConstants.STRING);
        } 
        catch (XPathExpressionException e) 
        {
            throw new RuntimeException(e);
        }
    }

    private List<Element> evaluate(XPathExpression expr, Node document)
    {
        final NodeList nodes;
        try {
            nodes = (NodeList) expr.evaluate(document,XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }

        final List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add( (Element) nodes.item( i ) );
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

    public InputStream scrape() throws ClientProtocolException, IOException 
    {
        final URI uri = URI.create(scheme+"://"+host+":"+port+"/api/xml");

        verbose("URI: "+uri);

        final HttpGet httpGet = new HttpGet(uri);

        final HttpResponse response;
        if ( isAuthEnabled() ) 
        {
            response = getClient().execute(getHost(), httpGet, clientContext);
        } else {
            response = getClient().execute(getHost(), httpGet);
        }
        final String content = EntityUtils.toString( response.getEntity() );
        if ( verbose ) {
            System.out.println("GOT: "+content );
        }        
        if ( response.getStatusLine().getStatusCode() != 200 ) {
            throw new IOException("Received "+response.getStatusLine());
        }
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
}