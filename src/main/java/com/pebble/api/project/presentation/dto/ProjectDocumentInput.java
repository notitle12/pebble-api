package com.pebble.api.project.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.post.presentation.dto.ArchitectureSpecInput;
import com.pebble.api.post.presentation.dto.DatabaseSchemaInput;
import com.pebble.api.post.presentation.dto.TableSpecInput;
import java.util.HashSet;
import java.util.Set;

/** 프로젝트 문서는 글 작성기와 동일한 구조화 블록 계약을 사용한다. */
public final class ProjectDocumentInput {
    private ProjectDocumentInput() { }
    public static void validate(JsonNode document) {
        object(document, Set.of("version","teamSize","role","sections","teamComposition","roles"));
        if (!document.path("version").isInt() || document.path("version").asInt()!=1
                || document.toString().length()>350_000) fail();
        JsonNode team=document.get("teamSize");
        if (team!=null&&!team.isNull()&&(!team.isInt()||team.asInt()<1||team.asInt()>10000)) fail();
        text(document.get("role"),10000,true);
        JsonNode composition=document.get("teamComposition");
        if(composition!=null){
            if(!composition.isArray()||composition.size()>20)fail();
            Set<String> jobs=new HashSet<>();int total=0;
            for(JsonNode item:composition){
                object(item,Set.of("role","count"));
                String job=text(item.get("role"),40,false);JsonNode count=item.get("count");
                if(!job.equals(job.strip())||job.isBlank()||!jobs.add(job.toLowerCase(java.util.Locale.ROOT))
                        ||count==null||!count.isInt()||count.asInt()<0||count.asInt()>10000)fail();
                total+=count.asInt();
            }
            if(total>10000||(total>0&&(team==null||team.isNull()||team.asInt()!=total))||(total==0&&team!=null&&!team.isNull()))fail();
        }
        JsonNode roles=document.get("roles");
        if(roles!=null){
            if(!roles.isArray()||roles.size()>10)fail();
            Set<String> unique=new HashSet<>();
            for(JsonNode role:roles){String value=text(role,200,false);if(value.isBlank()||!value.equals(value.strip())||!unique.add(value))fail();}
        }
        JsonNode sections=document.get("sections");
        if(sections==null||!sections.isArray()||sections.size()>6)fail();
        Set<String> seen=new HashSet<>();
        for(JsonNode section:sections){
            object(section,Set.of("key","blocks"));
            String key=section.path("key").asText();
            if(!Set.of("BACKGROUND","FEATURES","ARCHITECTURE","DATABASE","OUTCOMES","RETROSPECTIVE").contains(key)||!seen.add(key))fail();
            JsonNode blocks=section.get("blocks");
            if(blocks==null||!blocks.isArray()||blocks.size()>100)fail();
            for(JsonNode block:blocks){
                object(block,Set.of("type","content","language","title","alignment"));
                String type=block.path("type").asText();
                if(!Set.of("TEXT","HTML","MARKDOWN","CODE","TABLE","ARCHITECTURE","DB_SCHEMA").contains(type))fail();
                String content=text(block.get("content"),50000,false);
                if(Set.of("HTML","MARKDOWN").contains(type)&&content.contains("https://pebble.local.invalid/"))fail();
                text(block.get("title"),100,true);
                JsonNode language=block.get("language");
                if("CODE".equals(type)){
                    try{com.pebble.api.post.domain.CodeLanguage.valueOf(language==null?"":language.asText());}catch(IllegalArgumentException e){fail();}
                }else if(language!=null&&!language.isNull())fail();
                if(block.has("alignment")&&!Set.of("LEFT","CENTER","RIGHT").contains(block.path("alignment").asText()))fail();
                if("TABLE".equals(type))TableSpecInput.validate(content,"document");
                if("ARCHITECTURE".equals(type))ArchitectureSpecInput.validate(content,"document");
                if("DB_SCHEMA".equals(type))DatabaseSchemaInput.validate(content,"document");
            }
        }
    }
    private static String text(JsonNode node,int max,boolean optional){
        if(optional&&(node==null||node.isNull()))return "";
        if(node==null||!node.isTextual()||node.textValue().codePointCount(0,node.textValue().length())>max)fail();
        String value=node.textValue();
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c==0||Character.isLowSurrogate(c))fail();
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))fail();}
        }
        return value;
    }
    private static void object(JsonNode node,Set<String> fields){if(node==null||!node.isObject())fail();node.fieldNames().forEachRemaining(k->{if(!fields.contains(k))fail();});}
    private static void fail(){throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR,"document","프로젝트 문서 형식과 입력 길이를 확인해 주세요.");}
}
