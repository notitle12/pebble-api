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
    @Test void acceptsCustomJobNamesZeroCountsAndWrittenContribution(){
        var doc=document("TEXT","개요").put("teamSize",2).put("role","API 설계와 배포 자동화를 맡았습니다.\n".repeat(100));
        doc.putArray("teamComposition").addObject().put("role","프론트엔드").put("count",0);
        doc.withArray("teamComposition").addObject().put("role","프로덕트 디자이너").put("count",2);
        ProjectDocumentInput.validate(doc);
        doc.put("role","x".repeat(10001));
        assertThatThrownBy(()->ProjectDocumentInput.validate(doc)).isInstanceOf(ApplicationException.class);
    }

    @Test void acceptsTeamCompositionAndMultipleRoles(){
        var doc=document("TEXT","개요").put("teamSize",3);
        doc.putArray("teamComposition").addObject().put("role","BACKEND").put("count",2);
        doc.withArray("teamComposition").addObject().put("role","DESIGN").put("count",1);
        doc.putArray("roles").add("백엔드").add("프로젝트 리딩");
        ProjectDocumentInput.validate(doc);
        doc.put("teamSize",4);
        assertThatThrownBy(()->ProjectDocumentInput.validate(doc)).isInstanceOf(ApplicationException.class);
    }
    @Test void rejectsInvalidTeamAndRepeatedRoles(){
        var negative=document("TEXT","개요");negative.putArray("teamComposition").addObject().put("role","BACKEND").put("count",-1);
        var duplicate=document("TEXT","개요");duplicate.putArray("roles").add("백엔드").add("백엔드");
        var oversized=document("TEXT","개요");oversized.putArray("roles").add("x".repeat(201));
        for(var doc:java.util.List.of(negative,duplicate,oversized))assertThatThrownBy(()->ProjectDocumentInput.validate(doc)).isInstanceOf(ApplicationException.class);
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
