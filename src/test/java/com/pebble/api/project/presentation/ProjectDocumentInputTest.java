package com.pebble.api.project.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.project.presentation.dto.ProjectDocumentInput;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectDocumentInputTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode document(String type,String content){
        ObjectNode doc=mapper.createObjectNode().put("version",1).putNull("teamSize").put("role","");
        doc.putArray("sections").addObject().put("key","BACKGROUND").putArray("blocks").addObject()
                .put("type",type).put("content",content).putNull("language").putNull("title").put("alignment","LEFT");
        return doc;
    }
    @Test void acceptsNarrativeAndInlineImages(){
        ProjectDocumentInput.validate(document("HTML","<p>배경</p><img src=\"https://api.example/api/v1/projects/10/media/20/content\">"));
        ProjectDocumentInput.validate(document("TEXT","😀".repeat(50000)));
    }
    @Test void rejectsUnexpectedFieldsPendingImagesAndInvalidUnicode(){
        for(var doc:java.util.List.of(document("TEXT","bad\u0000"),document("TEXT","\ud800"),document("TEXT","x".repeat(50001)),
                document("HTML","<img src=\"https://pebble.local.invalid/images/id\">"),document("TABLE","{}"),document("ARCHITECTURE","{}"),document("DB_SCHEMA","{}"),document("UNKNOWN","x"),document("TEXT","ok").put("visibilityStatus","PUBLIC"))){
            assertThatThrownBy(()->ProjectDocumentInput.validate(doc)).isInstanceOf(ApplicationException.class);
        }
    }
    @Test void rejectsDuplicateSectionsWrongVersionAndTeamSize(){
        var duplicate=document("TEXT","okay");duplicate.withArray("sections").add(duplicate.withArray("sections").get(0).deepCopy());
        for(var doc:java.util.List.of(duplicate,document("TEXT","ok").put("version",2),document("TEXT","ok").put("teamSize",0),document("TEXT","ok").put("teamSize",1.5))){
            assertThatThrownBy(()->ProjectDocumentInput.validate(doc)).isInstanceOf(ApplicationException.class);
        }
    }
}
