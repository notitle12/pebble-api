package com.pebble.api.member.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.media.MediaRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BlogLinksRequest {
    private BlogLinksRequest() { }
    public static com.pebble.api.member.application.BlogLinksService.Input parse(JsonNode json) {
        if(json==null||!json.isObject())throw MediaRequest.invalid();
        fields(json,Set.of("githubUrl","sites"));
        JsonNode github=json.get("githubUrl");
        if(github==null||!(github.isNull()||github.isTextual()))throw MediaRequest.invalid();
        String githubUrl=github.isNull()?null:url(github.textValue());
        if(githubUrl!=null){var parsed=URI.create(githubUrl);if(!"github.com".equalsIgnoreCase(parsed.getHost())||parsed.getPath()==null||parsed.getPath().equals("/")||parsed.getPath().isEmpty())throw MediaRequest.invalid();}
        JsonNode sites=json.get("sites");
        if(sites==null||!sites.isArray()||sites.size()>5)throw MediaRequest.invalid();
        List<com.pebble.api.member.application.BlogLinksService.SiteInput> result=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(JsonNode site:sites){
            if(!site.isObject())throw MediaRequest.invalid();fields(site,Set.of("id","label","url"));
            Long id=null;
            if(site.has("id")){var node=site.get("id");if(!node.isTextual()||!node.textValue().matches("[1-9][0-9]{0,18}"))throw MediaRequest.invalid();try{id=Long.valueOf(node.textValue());}catch(NumberFormatException e){throw MediaRequest.invalid();}if(!seen.add(id))throw MediaRequest.invalid();}
            if(!site.path("label").isTextual()||!site.path("url").isTextual())throw MediaRequest.invalid();
            String label=site.get("label").textValue().trim();
            if(label.isBlank()||label.codePointCount(0,label.length())>50||label.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff))throw MediaRequest.invalid();
            result.add(new com.pebble.api.member.application.BlogLinksService.SiteInput(id,label,url(site.get("url").textValue())));
        }
        return new com.pebble.api.member.application.BlogLinksService.Input(githubUrl,List.copyOf(result));
    }
    private static String url(String value){
        if(value==null||value.length()>2048||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.isWhitespace(c)))throw MediaRequest.invalid();
        try {URI uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw MediaRequest.invalid();String normalized=uri.toASCIIString();if(normalized.length()>2048)throw MediaRequest.invalid();return normalized;}
        catch(IllegalArgumentException e){throw MediaRequest.invalid();}
    }
    private static void fields(JsonNode node,Set<String> allowed){node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw MediaRequest.invalid();});}
}
