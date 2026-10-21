package com.pebble.api.report.presentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.report.domain.*;
import com.pebble.api.global.exception.*;
import java.util.Set;
public final class ReportInput {
    private ReportInput() { }
    public record Submission(ReportReason reason,String detail) { }
    public static Submission submission(JsonNode json){
        fields(json,Set.of("reason","detail"));
        ReportReason reason=value(json,"reason",ReportReason.class);
        if(json.has("detail")&&!json.get("detail").isTextual())throw invalid();
        String detail=json.has("detail")?json.get("detail").textValue().strip():"";
        if(detail.codePointCount(0,detail.length())>1000 || detail.indexOf(0)>=0 || malformed(detail)
            || (reason==ReportReason.OTHER&&detail.isEmpty()))throw invalid();
        return new Submission(reason,detail);
    }
    public static ReportAction action(JsonNode json){fields(json,Set.of("action"));return value(json,"action",ReportAction.class);}
    public static long id(String text){
        if(text==null||!text.matches("[1-9][0-9]{0,18}"))throw invalid();
        try{return Long.parseLong(text);}catch(NumberFormatException error){throw invalid();}
    }
    private static boolean malformed(String value){
        for(int i=0;i<value.length();i++)if(Character.isSurrogate(value.charAt(i))){
            if(!Character.isHighSurrogate(value.charAt(i))||i+1==value.length()||!Character.isLowSurrogate(value.charAt(++i)))return true;
        }
        return false;
    }
    private static <E extends Enum<E>> E value(JsonNode json,String name,Class<E> type){
        if(!json.has(name)||!json.get(name).isTextual())throw invalid();
        try{return Enum.valueOf(type,json.get(name).textValue());}catch(IllegalArgumentException error){throw invalid();}
    }
    private static void fields(JsonNode json,Set<String> allowed){
        if(json==null||!json.isObject())throw invalid();
        json.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw invalid();});
    }
    public static ApplicationException invalid(){return new ApplicationException(GlobalErrorCode.INVALID_REQUEST);}
}
