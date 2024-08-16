package p3;

import java.io.*;
import java.util.*;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.internet.MimeBodyPart;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v126.network.Network;
import org.openqa.selenium.devtools.v126.network.model.Request;
import org.openqa.selenium.devtools.v126.network.model.RequestId;
import org.openqa.selenium.devtools.v126.network.model.Response;

public class p3 {
    private static final String APPLICATION_NAME = "ExtractingUrls";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_SEND);
    private static final String CREDENTIALS_FILE_PATH = "details.json";
    private static final String CSV_FILENAME = "responses.csv";

    public static void main(String[] args) {
        ChromeDriver driver = null;
        try {
            driver = new ChromeDriver();
            driver.get("https://timesofindia.indiatimes.com/");
            Thread.sleep(6000);

            DevTools devTools = driver.getDevTools();
            devTools.createSession();
            devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty()));

            Set<String> filteredUrls = new HashSet<>();
            setupNetworkListeners(devTools, filteredUrls);

            scrollPage(driver);
           // Wait for any remaining requests to complete
            sendEmailWithAttachment("mishraanurag7104@gmail.com", CSV_FILENAME);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    private static void setupNetworkListeners(DevTools devTools, Set<String> filteredUrls) {
        devTools.addListener(Network.requestWillBeSent(), requestConsumer -> {
            Request request = requestConsumer.getRequest();
            String url = request.getUrl();
            if (url.contains("ads?")) {
                filteredUrls.add(url);
            }
        });

        devTools.addListener(Network.responseReceived(), responseConsumer -> {
            Response response = responseConsumer.getResponse();
            String url = response.getUrl();
            if (filteredUrls.contains(url)) {
                processResponse(devTools, responseConsumer.getRequestId(), url);
            }
        });
    }

    private static void processResponse(DevTools devTools, RequestId requestId, String url) {
        try {
            Network.GetResponseBodyResponse bodyResponse = devTools.send(Network.getResponseBody(requestId));
            String body = bodyResponse.getBody();

            if (bodyResponse.getBase64Encoded()) {
                body = new String(Base64.getDecoder().decode(body));
            }
            System.out.println("Response Body: " + body);

            List<Object> nestedArrays = parseJsonResponse(body);
            if (nestedArrays != null) {
                saveToCSV(nestedArrays, CSV_FILENAME);
            }

        } catch (Exception e) {
            System.out.println("Failed to process response for URL: " + url);
            e.printStackTrace();
        }
    }

    private static List<Object> parseJsonResponse(String body) {
        try {
            JSONObject jsonObject = new JSONObject(body);
            String firstKey = jsonObject.keys().next();
            JSONArray jsonArray = jsonObject.optJSONArray(firstKey);

            List<Object> nestedArrays = new ArrayList<>();
            nestedArrays.add(firstKey);

            for (int i = 0; i < jsonArray.length(); i++) {
                Object element = jsonArray.get(i);
                if (element instanceof JSONArray) {
                    JSONArray innerArray = (JSONArray) element;
                    for (int j = 0; j < innerArray.length(); j++) {
                        nestedArrays.add(innerArray.get(j));
                    }
                }
            }

            System.out.println("Nested Array: " + nestedArrays);
            return nestedArrays;
        } catch (Exception e) {
            System.out.println("Failed to parse JSON or fetch the first key.");
            e.printStackTrace();
            return null;
        }
    }

    private static void scrollPage(ChromeDriver driver) throws InterruptedException {
    	 JavascriptExecutor jse = (JavascriptExecutor) driver;
    	    Number pageHeight = (Number) jse.executeScript("return document.body.scrollHeight;");
    	    long scrollHeight = 0;

    	    while (scrollHeight < pageHeight.longValue()-1) {
    	        jse.executeScript("window.scrollBy(0, window.innerHeight);");
    	        Thread.sleep(6000); // Wait for the page to load new content

    	        Number currentScrollHeight = (Number) jse.executeScript("return window.pageYOffset + window.innerHeight;");
    	        scrollHeight = currentScrollHeight.longValue();
    	        
    	        // Recalculate page height in case it changes due to dynamic content
    	        pageHeight = (Number) jse.executeScript("return document.body.scrollHeight;");

    	        System.out.println("Scroll Height: " + scrollHeight);
    	        System.out.println("Page Height: " + pageHeight);
    	        System.out.println("Current Scroll Height: " + currentScrollHeight);
        }
    }

    private static void saveToCSV(List<Object> nestedArrays, String filename) {
        boolean fileExists = new java.io.File(filename).exists();
        try (FileWriter writer = new FileWriter(filename, true)) {
            if (!fileExists) {
                writer.append("IU_Parts,Creative_Id,Name_Id,Extra_Id,Order_Id\n");
            }

            for (Object obj : nestedArrays) {
                writer.append(obj.toString()).append(",");
            }

            writer.append("\n");
            writer.flush();
        } catch (IOException e) {
            System.out.println("Failed to save data to CSV.");
            e.printStackTrace();
        }
    }

    private static void sendEmailWithAttachment(String from, String filename) throws Exception {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Gmail service = new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME)
                .build();

        MimeMessage emailContent = createEmailWithAttachment(from, from, "Responses CSV", "Please find the attached CSV file.", filename);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        emailContent.writeTo(buffer);
        byte[] rawMessageBytes = buffer.toByteArray();
        String encodedEmail = Base64.getEncoder().encodeToString(rawMessageBytes);

        Message message = new Message();
        message.setRaw(encodedEmail);

        message = service.users().messages().send("me", message).execute();

        System.out.println("Email sent with ID: " + message.getId());
    }

    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        InputStream in = p3.class.getClassLoader().getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    private static MimeMessage createEmailWithAttachment(String to, String from, String subject, String bodyText, String filePath) throws MessagingException {
        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);

        MimeMessage email = new MimeMessage(session);

        email.setFrom(new InternetAddress(from));
        email.addRecipient(javax.mail.Message.RecipientType.TO, new InternetAddress(to));
        email.setSubject(subject);

        MimeBodyPart mimeBodyPart = new MimeBodyPart();
        mimeBodyPart.setContent(bodyText, "text/plain");

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(mimeBodyPart);

        MimeBodyPart attachment = new MimeBodyPart();
        DataSource source = new FileDataSource(filePath);

        attachment.setDataHandler(new DataHandler(source));
        attachment.setFileName(filePath);

        multipart.addBodyPart(attachment);
        email.setContent(multipart);

        return email;
    }
}
