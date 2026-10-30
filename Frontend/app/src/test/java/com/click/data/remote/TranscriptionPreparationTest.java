package com.click.data.remote;

import java.util.*;
import java.util.concurrent.*;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class TranscriptionPreparationTest {
 static final class ControlledExecutor extends AbstractExecutorService {
  final List<Runnable> pending=new ArrayList<>();boolean closed;
  public void shutdown(){closed=true;}
  public List<Runnable> shutdownNow(){closed=true;return pending;}
  public boolean isShutdown(){return closed;}
  public boolean isTerminated(){return closed;}
  public boolean awaitTermination(long t,TimeUnit u){return closed;}
  public void execute(Runnable r){pending.add(r);}
  void runNext(){pending.remove(0).run();}
 }
 private String contract() throws Exception {
  JSONObject props=new JSONObject().put("provider",new JSONObject().put("const","self_hosted"))
    .put("model",new JSONObject().put("const",PrivateSpeechContract.MODEL));
  JSONObject schema=new JSONObject().put("properties",props);
  JSONObject content=new JSONObject().put("application/json",new JSONObject().put("schema",schema));
  JSONObject responses=new JSONObject().put("200",new JSONObject().put("content",content));
  JSONObject operation=new JSONObject().put("post",new JSONObject().put("responses",responses));
  return new JSONObject().put("paths",new JSONObject().put("/api/v1/speech/transcriptions",operation)).toString();
 }
 @Test public void preparesWhileRecordingAndDoesNotFetchAgainBeforeSameUpload() throws Exception {
  ControlledExecutor executor=new ControlledExecutor();AuthenticatedHttpClient http=mock(AuthenticatedHttpClient.class);
  when(http.post(anyString(),anyString(),anyInt())).thenReturn(new HttpResult(200,"{\"provider\":\"self_hosted\",\"model\":\"openai/whisper-large-v3\",\"text\":\"글자 크게\"}",null));
  TranscriptionGateway gateway=new TranscriptionGateway(http,executor);
  try(MockedStatic<AuthGateway> auth=mockStatic(AuthGateway.class)) {
   auth.when(()->AuthGateway.get("/openapi.json",null)).thenReturn(new HttpResult(200,contract(),null));
   gateway.prepare();executor.runNext();
   verifyNoInteractions(http);
   TranscriptionGateway.Callback callback=mock(TranscriptionGateway.Callback.class);
   gateway.transcribe(new byte[]{1,2},callback);executor.runNext();shadowOf(Looper.getMainLooper()).idle();
   auth.verify(()->AuthGateway.get("/openapi.json",null),times(1));
   verify(callback).onSuccess("글자 크게");
   gateway.prepare();executor.runNext();
   auth.verify(()->AuthGateway.get("/openapi.json",null),times(2));
  }
 }
 @Test public void unverifiedContractNeverUploadsAudio() throws Exception {
  ControlledExecutor executor=new ControlledExecutor();AuthenticatedHttpClient http=mock(AuthenticatedHttpClient.class);
  TranscriptionGateway gateway=new TranscriptionGateway(http,executor);
  try(MockedStatic<AuthGateway> auth=mockStatic(AuthGateway.class)) {
   auth.when(()->AuthGateway.get("/openapi.json",null)).thenReturn(new HttpResult(200,"{}",null));
   gateway.prepare();executor.runNext();
   TranscriptionGateway.Callback callback=mock(TranscriptionGateway.Callback.class);
   gateway.transcribe(new byte[]{1,2},callback);executor.runNext();shadowOf(Looper.getMainLooper()).idle();
   verifyNoInteractions(http);verify(callback).onFailure(any());
  }
 }
 @Test public void cancellationPreventsQueuedAudioUpload() throws Exception {
  ControlledExecutor executor=new ControlledExecutor();AuthenticatedHttpClient http=mock(AuthenticatedHttpClient.class);
  TranscriptionGateway gateway=new TranscriptionGateway(http,executor);
  try(MockedStatic<AuthGateway> auth=mockStatic(AuthGateway.class)) {
   auth.when(()->AuthGateway.get("/openapi.json",null)).thenReturn(new HttpResult(200,contract(),null));
   gateway.prepare();gateway.transcribe(new byte[]{1,2},mock(TranscriptionGateway.Callback.class));gateway.cancel();
   executor.runNext();executor.runNext();verifyNoInteractions(http);
  }
 }
}
