package unidue.ub.elisaconnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import unidue.ub.elisaconnector.client.ElisaClient;
import unidue.ub.elisaconnector.client.SubjectClient;
import unidue.ub.elisaconnector.model.*;
import unidue.ub.elisaconnector.service.MailContentBuilder;

import java.util.List;
import java.util.regex.Pattern;


@Controller
public class ElisaController {

    private final Logger log = LoggerFactory.getLogger(ElisaController.class);

    // the caller id assigned by the hbz
    @Value("${libintel.elisa.callerid}")
    private String callerID;

    // the secret provided by the hbz
    @Value("${libintel.elisa.secret}")
    private String secret;

    // the standard email address to send mails if elisa submission does not work
    @Value("${libintel.eavs.email.default}")
    private String defaultEavEamil;

    // standard elisa user getting the notepads if no subject librarian can be chosen
    @Value("${libintel.elisa.userid.default}")
    private String defaultElisaUserid;

    private final JavaMailSender emailSender;

    private final ElisaClient elisaClient;

    private final SubjectClient subjectClient;

    private final MailContentBuilder mailContentBuilder;


    @Autowired
    public ElisaController(ElisaClient elisaClient, SubjectClient subjectClient, JavaMailSender emailSender, MailContentBuilder mailContentBuilder) {
        this.elisaClient = elisaClient;
        this.subjectClient = subjectClient;
        this.emailSender = emailSender;
        this.mailContentBuilder = mailContentBuilder;
    }

    // endpoint to process the data from the html form
    @PostMapping("/sendEav")
    public ResponseEntity<?> receiveEav(@RequestParam String isbn,
                                        @RequestParam String title,
                                        @RequestParam String contributor,
                                        @RequestParam String edition,
                                        @RequestParam String publisher,
                                        @RequestParam String year,
                                        @RequestParam String price,
                                        @RequestParam String subjectarea,
                                        @RequestParam String source,
                                        @RequestParam String comment,
                                        @RequestParam String name,
                                        @RequestParam String libraryaccountNumber,
                                        @RequestParam String emailAddress,
                                        @RequestParam boolean response,
                                        @RequestParam boolean essen,
                                        @RequestParam boolean duisburg,
                                        @RequestParam String requestPlace) {
        // for an easier handling store all paramters in the POJO RequestData
        RequestData requestData = new RequestData(isbn, title, contributor, edition, publisher, year, price,
                subjectarea, source, comment, name, libraryaccountNumber, emailAddress, response, essen, duisburg, requestPlace);

        // ISBN regular expression test for ISBN
        Pattern patternISBN = Pattern.compile("^(97([89]))?\\d{9}(\\d|X)$");
        if (isbn.contains("-"))
            isbn = isbn.replace("-", "");

        // if it is ISBN, try to upload it to elisa
        if (patternISBN.matcher(isbn).find()) {
            log.info("received Request to send ISBN " + requestData.isbn + "to ELi:SA");

            // try to get the corresponding user account from the settings backend. If no user id can be obtained, send the email
            String userID;
            try {
                userID = subjectClient.getElisaAccount(subjectarea);
            } catch (Exception e) {
                sendMail(defaultEavEamil, requestData, "Der zuständige ELi:SA account konnte nicht gefunden werden");
                log.warn("could not retrieve the userID for subjectArea " + subjectarea);
                return ResponseEntity.badRequest().body("could not retreive Elisa account id");
            }
            if (userID == null)
                return ResponseEntity.badRequest().body("could not retreive Elisa account id");

            log.info("setting elisa id to " + userID);

            // prepare isbns for sending to elisa
            CreateListRequest createListRequest = new CreateListRequest(userID, "Anschaffungsvorschlag");

            TitleData requestedTitle = new TitleData(isbn);

            // prepare the intern note with the data about the user and his comments. Add the email for notification.
            String noteIntern = name + " (" + emailAddress + "): " + comment + "\n Literaturangebe von: " + source;
            if (response) {
                noteIntern += "\n Bitte den Nutzer benachrichtigen.";
            }
            requestedTitle.setNotizIntern(noteIntern);

            // prepare the library note
            String note = "";
            if (essen) {
                note += "E :1, ";
            }
            if (duisburg) {
                if (essen)
                    note += "";
                note += "D :1,  , ";
            }
            note += "VM für " + libraryaccountNumber + " (" + name + ") in " + requestPlace;
            requestedTitle.setNotiz(note);

            // prepare the creation request
            createListRequest.addTitle(new Title(requestedTitle));

            //prepare the authentication request
            AuthenticationRequest authenticationRequest = new AuthenticationRequest(callerID, secret);
            try {
                // perform authentication
                AuthenticationResponse authenticationResponse = elisaClient.getToken(authenticationRequest);
                // if successful try to create list
                if (authenticationResponse.getErrorcode() == 0) {
                    createListRequest.setToken(authenticationResponse.getToken());
                    CreateListResponse createListResponse = elisaClient.createList(createListRequest);
                    if (createListResponse.getErrorcode() == 0) {
                        log.info("successfully create elisa list");
                        return ResponseEntity.ok("List created");
                    } else {
                        // if creation fails, send email to standard address
                        sendMail(defaultEavEamil, requestData, "ELi:SA-Antwort: " + createListResponse.getErrorMessage());
                        log.warn("could not create list. Reason: " + createListResponse.getErrorMessage() );
                        return ResponseEntity.badRequest().body(createListResponse.getErrorMessage());
                    }
                // if authentication fails, send email to standard address
                } else {
                    log.error("elisa authentication failed. Reason: " + authenticationResponse.getErrorMessage());
                    sendMail(defaultEavEamil, requestData, "Die ELi:SA -Authentifizierung ist fehlgeschlagen");                    return ResponseEntity.badRequest().body("no token received");
                }

            } catch (Exception e) {
                log.error("could not connect to elisa API");
                sendMail(defaultEavEamil, requestData, "ELi:SA API nicht erreichbar");
                return ResponseEntity.badRequest().body("could not connect to elisa API");
            }
        } else {
            log.warn("no isbn given");
            sendMail(defaultEavEamil, requestData, "Es wurde keine ISBN angegeben");
            return ResponseEntity.ok().body("Please provide an ISBN");
        }
    }

    @PostMapping("/sendToElisa")
    public ResponseEntity<?> sendToElisa(@RequestBody List<Title> titles, @RequestBody String userID) {
        AuthenticationRequest authenticationRequest = new AuthenticationRequest(callerID, secret);
        AuthenticationResponse authenticationResponse = elisaClient.getToken(authenticationRequest);
        CreateListRequest createListRequest = new CreateListRequest(userID, "Anschaffungsvorschlag");
        createListRequest.setToken(authenticationResponse.getToken());
        createListRequest.setTitleList(titles);

        return ResponseEntity.accepted().build();
    }

    private void sendMail(String to, RequestData requestData, String reason) {
        MimeMessagePreparator messagePreparator = mimeMessage -> {
            MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage);
            messageHelper.setFrom("eike.spielberg@uni-due.de");
            messageHelper.setTo(to);
            String text = mailContentBuilder.build(requestData, reason);
            messageHelper.setText(text, true);
            messageHelper.setSubject("Anschaffungsvorschlag");
        };
        emailSender.send(messagePreparator);
        log.info("sent email to " + to);
    }
}