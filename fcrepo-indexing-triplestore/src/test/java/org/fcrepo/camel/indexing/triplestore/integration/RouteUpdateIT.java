/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.camel.indexing.triplestore.integration;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.activemq.ActiveMQComponent;
import org.apache.camel.component.mock.MockEndpoint;
//import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.test.spring.CamelSpringBootRunner;
//import org.springframework.test.context.junit4.SpringRunner;
import org.apache.camel.test.spring.junit5.CamelSpringTest;
//import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.support.AnnotationConfigContextLoader;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.ClassMode;
import java.net.URI;

import static com.jayway.awaitility.Awaitility.await;
import static java.lang.Integer.parseInt;
import static org.apache.camel.util.ObjectHelper.loadResourceAsStream;
import static org.fcrepo.camel.indexing.triplestore.integration.TestUtils.ASSERT_PERIOD_MS;
import static org.fcrepo.camel.indexing.triplestore.integration.TestUtils.createFcrepoClient;
import static org.fcrepo.camel.indexing.triplestore.integration.TestUtils.getEvent;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Test the route workflow.
 *
 * @author Aaron Coburn
 * @since 2015-04-10
 */
//@RunWith(CamelSpringBootRunner.class)
//@RunWith(SpringRunner.class)
//@RunWith(SpringJUnit4ClassRunner.class)
//@ContextConfiguration(classes = {RouteUpdateIT.ContextConfig.class})
@CamelSpringTest
@ContextConfiguration
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
public class RouteUpdateIT {

    final private Logger logger = getLogger(RouteUpdateIT.class);

    private static FusekiServer server = null;

    private static final String AS_NS = "https://www.w3.org/ns/activitystreams#";

    private String fullPath = "";

    private static final String FUSEKI_PORT = System.getProperty(
            "fuseki.dynamic.test.port", "8080"
    );

    private static final String FCREPO_PORT = System.getProperty(
            "fcrepo.dynamic.test.port", "8080"
    );

    private static final String JMS_PORT = System.getProperty(
            "fcrepo.dynamic.jms.port", "61616"
    );

    //@MockBean
    //@Autowired
    @Produce("direct:start")
    protected ProducerTemplate template;

    @Autowired
    private CamelContext camelContext;

    @BeforeClass
    public static void beforeClass() {
        System.setProperty("triplestore.indexing.enabled", "true");
        System.setProperty("triplestore.indexing.predicate", "true");
        System.setProperty("triplestore.baseUrl", "http://localhost:" + FUSEKI_PORT + "/fuseki/test/update");
        System.setProperty("jms.brokerUrl", "tcp://localhost:" + JMS_PORT);
        System.setProperty("triplestore.input.stream", "direct:start");
        System.setProperty("triplestore.reindex.stream", "direct:reindex");
        System.setProperty("fcrepo.baseUrl", "http://localhost:" + FCREPO_PORT + "/fcrepo/rest");
    }

    @After
    public void tearDownFuseki() throws Exception {
        logger.info("Stopping EmbeddedFusekiServer");
        server.stop();
    }

    @Before
    public void setUpFuseki() throws Exception {
        final FcrepoClient client = createFcrepoClient();
        final FcrepoResponse res = client.post(URI.create("http://localhost:" + FCREPO_PORT + "/fcrepo/rest"))
                .body(loadResourceAsStream("indexable.ttl"), "text/turtle").perform();
        fullPath = res.getLocation().toString();
        logger.info("full path {}", fullPath);

        logger.info("Starting EmbeddedFusekiServer on port {}", FUSEKI_PORT);
        final Dataset ds = DatasetFactory.createTxnMem(); //new DatasetImpl(createDefaultModel());
        server = FusekiServer.create()
                .verbose(true)
                .port(parseInt(FUSEKI_PORT))
                .contextPath("/fuseki")
                .add("/test", ds, true)
                .build();
        server.start();
    }

    @DirtiesContext
    @Test
    public void testAddedEventRouter() throws Exception {
        final String fusekiEndpoint = "mock:http:localhost:" + FUSEKI_PORT + "/fuseki/test/update";
        final String fcrepoEndpoint = "mock:fcrepo:http://localhost:" + FCREPO_PORT + "/fcrepo/rest";
        final String fusekiBase = "http://localhost:" + FUSEKI_PORT + "/fuseki/test";

        // final var context = camelContext.adapt(ModelCamelContext.class);

        AdviceWith.adviceWith(camelContext, "FcrepoTriplestoreRouter", a -> {
            a.mockEndpoints("*");
        });

        AdviceWith.adviceWith(camelContext, "FcrepoTriplestoreIndexer", a -> {
            a.mockEndpoints("*");
        });

        AdviceWith.adviceWith(camelContext, "FcrepoTriplestoreUpdater", a -> {
            a.mockEndpoints("*");
        });

        final var fusekiMockEndpoint = MockEndpoint.resolve(camelContext, fusekiEndpoint);
        fusekiMockEndpoint.expectedMessageCount(1);
        fusekiMockEndpoint.expectedHeaderReceived(Exchange.HTTP_RESPONSE_CODE, 200);
        final var deleteEndpoint = MockEndpoint.resolve(camelContext, "mock://direct:delete.triplestore");
        deleteEndpoint.expectedMessageCount(0);
        deleteEndpoint.setAssertPeriod(ASSERT_PERIOD_MS);
        final var updateEndpoint = MockEndpoint.resolve(camelContext, "mock://direct:update.triplestore");
        updateEndpoint.expectedMessageCount(1);

        final var fcrepoMockEndpoint = MockEndpoint.resolve(camelContext, fcrepoEndpoint);
        fcrepoMockEndpoint.expectedMessageCount(2);

        await().until(TestUtils.triplestoreCount(fusekiBase, fullPath), equalTo(0));

        logger.info("fullPath={}", fullPath);
        template.sendBody("direct:start", getEvent(fullPath, AS_NS + "Create"));

        await().until(TestUtils.triplestoreCount(fusekiBase, fullPath), greaterThanOrEqualTo(1));
        MockEndpoint.assertIsSatisfied(fusekiMockEndpoint, fcrepoMockEndpoint, deleteEndpoint, updateEndpoint);

    }

    @Configuration
    static class ContextConfig {
        @Bean
        public ActiveMQComponent broker() {
            final var component = new ActiveMQComponent();
            component.setBrokerURL("tcp://localhost:" + JMS_PORT);
            return component;
        }

    }
}
