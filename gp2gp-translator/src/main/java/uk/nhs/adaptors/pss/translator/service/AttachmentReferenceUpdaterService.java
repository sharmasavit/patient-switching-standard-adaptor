package uk.nhs.adaptors.pss.translator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.nhs.adaptors.pss.translator.exception.AttachmentNotFoundException;
import uk.nhs.adaptors.pss.translator.exception.InlineAttachmentProcessingException;
import uk.nhs.adaptors.pss.translator.mhs.model.InboundMessage;
import uk.nhs.adaptors.pss.translator.model.InlineAttachment;
import uk.nhs.adaptors.pss.translator.storage.StorageManagerService;

import javax.xml.bind.ValidationException;
import java.text.ParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class AttachmentReferenceUpdaterService {

    private final StorageManagerService storageManagerService;
    public String updateReferenceToAttachment(List<InboundMessage.Attachment> attachments, String conversationId, String payloadStr)
            throws ValidationException, AttachmentNotFoundException, InlineAttachmentProcessingException {
        if (conversationId == null || conversationId.isEmpty()) {
            throw new ValidationException("ConversationId cannot be null or empty");
        }

        String resultPayload = payloadStr;

        if (attachments != null) {

            for (InboundMessage.Attachment attachment : attachments) {

                try {
                    InlineAttachment inlineAttachment = new InlineAttachment(attachment);
                    String filename = inlineAttachment.getOriginalFilename();

                    // find "local" reference by finding the following:
                    // "<reference value=\"file://localhost/${filename}\" />"
                    var patternStr = String.format("<reference value=\"file://localhost/%s\" \\/>", filename);
                    Pattern pattern = Pattern.compile(patternStr);
                    Matcher matcher = pattern.matcher(resultPayload);

                    var matchFound = matcher.find();

                    if (matchFound) {
                        // update local ref with external reference
                        String fileLocation = storageManagerService.getFileLocation(filename);
                        var replaceStr = String.format("<reference value=\"%s\" />", fileLocation);
                        resultPayload = matcher.replaceAll(replaceStr);
                    } else {
                        var message = String.format("Could not find file %s in payload", filename);
                        throw new AttachmentNotFoundException(message);
                    }
                } catch (ParseException ex) {
                    throw new InlineAttachmentProcessingException("Unable to parse inline attachment description: " + ex.getMessage());
                } catch (AttachmentNotFoundException e) {
                    throw new AttachmentNotFoundException(e.getMessage());
                }
            }
        }

        return resultPayload;
    }
}