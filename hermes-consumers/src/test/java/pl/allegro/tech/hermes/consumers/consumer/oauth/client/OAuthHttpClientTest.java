package pl.allegro.tech.hermes.consumers.consumer.oauth.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static pl.allegro.tech.hermes.consumers.consumer.oauth.client.OAuthTokenRequest.oAuthTokenRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.HttpClient;
import org.junit.Test;

public class OAuthHttpClientTest {

  @Test
  public void shouldRestoreInterruptStatusWhenTokenRequestIsInterrupted() throws Exception {
    // given: a token endpoint that accepts the connection but never responds,
    // so Request.send() blocks inside OAuthHttpClient.performHttpRequest
    try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      CountDownLatch requestAccepted = new CountDownLatch(1);
      Thread acceptor =
          new Thread(
              () -> {
                try {
                  Socket socket = serverSocket.accept();
                  requestAccepted.countDown();
                  // hold the connection open without responding until the test ends
                  Thread.sleep(TimeUnit.SECONDS.toMillis(60));
                  socket.close();
                } catch (Exception ignored) {
                }
              });
      acceptor.setDaemon(true);
      acceptor.start();

      HttpClient httpClient = new HttpClient();
      httpClient.start();
      try {
        OAuthHttpClient oAuthClient = new OAuthHttpClient(httpClient, new ObjectMapper());
        OAuthTokenRequest request =
            oAuthTokenRequest()
                .withUrl("http://127.0.0.1:" + serverSocket.getLocalPort() + "/oauth/token")
                .withGrantType(OAuthTokenRequest.GrantTypeValue.CLIENT_CREDENTIALS)
                .withClientId("client-id")
                .withClientSecret("client-secret")
                .withRequestTimeout(60_000)
                .withSocketTimeout(60_000)
                .build();

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedWhenCaught = new AtomicBoolean(false);
        Thread worker =
            new Thread(
                () -> {
                  try {
                    oAuthClient.getToken(request);
                  } catch (Throwable e) {
                    thrown.set(e);
                    interruptedWhenCaught.set(Thread.currentThread().isInterrupted());
                  }
                });

        // when: the worker blocks in send() waiting for the response and gets interrupted
        worker.start();
        assertTrue(
            "server should accept the token request", requestAccepted.await(30, TimeUnit.SECONDS));
        awaitBlocked(worker);
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(30));

        // then
        assertFalse("worker thread should finish after being interrupted", worker.isAlive());
        assertTrue(
            "expected OAuthTokenRequestException but got: " + thrown.get(),
            thrown.get() instanceof OAuthTokenRequestException);
        assertEquals(InterruptedException.class, thrown.get().getCause().getClass());
        assertTrue(
            "interrupt status should be restored before OAuthTokenRequestException is thrown",
            interruptedWhenCaught.get());
      } finally {
        httpClient.stop();
      }
    }
  }

  private static void awaitBlocked(Thread worker) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      Thread.State state = worker.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
        return;
      }
      Thread.sleep(10);
    }
    fail("worker thread did not block waiting for the token response");
  }
}
