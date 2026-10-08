package com.pebble.api.member.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.global.media.R2ObjectStorage;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BlogToolsIntegrationTest extends AuthenticationTestSupport {
 @Autowired MockMvc mvc;
 @Autowired MemberRepository members;
 @Autowired AccessTokenService tokens;
 @Autowired JdbcTemplate jdbc;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
 @MockitoBean R2ObjectStorage storage;
 Member owner,other;
 @BeforeEach void setup(){owner=blog();other=blog();when(storage.signedUrl(anyString())).thenAnswer(c->"https://images.example.test/"+c.getArgument(0));}
 @Test void visitCookieDeduplicatesAndReadDoesNotWrite()throws Exception{
  mvc.perform(get(path()+"/visits")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalVisitors").value(0));
  var first=mvc.perform(post(path()+"/visits").header("Origin","http://localhost:3000")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalVisitors").value(1)).andExpect(jsonPath("$.data.todayVisitors").value(1)).andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul")).andReturn();
  String cookie=first.getResponse().getHeader("Set-Cookie");assertThat(cookie).contains("HttpOnly","SameSite=Lax","Path=/api/v1/blogs");
  String visitor=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));
  mvc.perform(post(path()+"/visits").header("Origin","http://localhost:3000").cookie(new Cookie(BlogToolsController.VISITOR_COOKIE,visitor))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalVisitors").value(1));
  mvc.perform(get(path()+"/visits")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalVisitors").value(1));
  mvc.perform(post(path()+"/visits").header("Origin","http://localhost:3000").cookie(new Cookie(BlogToolsController.VISITOR_COOKIE,UUID.randomUUID().toString()))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalVisitors").value(2));
  assertThat(jdbc.queryForList("select visitor_hash from blog_visit_daily where member_id=?",String.class,owner.getId())).allMatch(v->v.matches("[a-f0-9]{64}"));
 }
 @Test void visitRequiresSingleAllowedOriginAndStrictRequest()throws Exception{
  mvc.perform(post(path()+"/visits")).andExpect(status().isForbidden());
  for(String origin:new String[]{"null","https://evil.example"})mvc.perform(post(path()+"/visits").header("Origin",origin)).andExpect(status().isForbidden());
  mvc.perform(post(path()+"/visits").header("Origin","http://localhost:3000","http://localhost:3000")).andExpect(status().isForbidden());
  mvc.perform(post(path()+"/visits?q=x").header("Origin","http://localhost:3000")).andExpect(status().isBadRequest());
  mvc.perform(post(path()+"/visits").header("Origin","http://localhost:3000").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/v1/blogs/missing-blog/visits").header("Origin","http://localhost:3000")).andExpect(status().isNotFound());
  mvc.perform(options(path()+"/visits").header("Origin","http://localhost:3000").header("Access-Control-Request-Method","POST")).andExpect(status().isOk());
 }
 @Test void githubAndLinksArePublicOnlyAfterOwnAuthenticatedSave()throws Exception{
  String json="{\"githubUrl\":\"https://github.com/notitle12\",\"sites\":[]}";
  mvc.perform(put("/api/v1/members/me/blog-links").contentType("application/json").content(json)).andExpect(status().isForbidden());
  mvc.perform(put("/api/v1/members/me/blog-links").header("Authorization",bearer(owner)).contentType("application/json").content(json)).andExpect(status().isOk());
  mvc.perform(get(path()+"/links")).andExpect(status().isOk()).andExpect(jsonPath("$.data.githubUrl").value("https://github.com/notitle12")).andExpect(jsonPath("$.data.sites.length()").value(0));
  mvc.perform(get("/api/v1/blogs/"+other.getHandle()+"/links")).andExpect(jsonPath("$.data.githubUrl").isEmpty());
  for(String url:new String[]{"javascript:alert(1)","http://github.com/notitle12","https://github.com.evil.test/notitle12","https://user:password@github.com/notitle12"})mvc.perform(put("/api/v1/members/me/blog-links").header("Authorization",bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("githubUrl",url,"sites",java.util.List.of())))).andExpect(status().isBadRequest());
  mvc.perform(put("/api/v1/members/me/blog-links").header("Authorization",bearer(owner)).contentType("application/json").content("{\"githubUrl\":null,\"sites\":[{\"label\":\"Site\",\"url\":\"https://example.test\"}]}")).andExpect(status().isBadRequest());
 }
 @Test void uploadedLogoPreservesOwnershipAndQueuesRemovedImage()throws Exception{
  var image=new java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
  String input="{\"githubUrl\":null,\"sites\":[{\"label\":\"내 사이트\",\"url\":\"https://example.test\"}]}";
  var response=mvc.perform(multipart("/api/v1/members/me/blog-links").file(new MockMultipartFile("logo0","logo.png","image/png",bytes.toByteArray())).param("links",input).header("Authorization",bearer(owner)).with(r->{r.setMethod("PUT");return r;})).andExpect(status().isOk()).andExpect(jsonPath("$.data.sites[0].logoUrl").value(org.hamcrest.Matchers.startsWith("https://images.example.test/member/"))).andReturn();
  String id=mapper.readTree(response.getResponse().getContentAsString()).at("/data/sites/0/id").asText();
  String saved=mapper.writeValueAsString(java.util.Map.of("githubUrl","https://github.com/notitle12","sites",java.util.List.of(java.util.Map.of("id",id,"label","변경 사이트","url","https://example.test/new"))));
  mvc.perform(put("/api/v1/members/me/blog-links").header("Authorization",bearer(other)).contentType("application/json").content(saved)).andExpect(status().isNotFound());
  mvc.perform(put("/api/v1/members/me/blog-links").header("Authorization",bearer(owner)).contentType("application/json").content(saved)).andExpect(status().isOk()).andExpect(jsonPath("$.data.sites[0].id").value(id));
  String key=jdbc.queryForObject("select logo_storage_key from blog_link where id=?",String.class,Long.valueOf(id));
  assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?",Integer.class,key)).isZero();
  mvc.perform(put("/api/v1/members/me/blog-links").header("Authorization",bearer(owner)).contentType("application/json").content("{\"githubUrl\":null,\"sites\":[]}")).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?",Integer.class,key)).isEqualTo(1);
 }
 @Test void invalidLogoDoesNotPartiallySaveGithub()throws Exception{
  mvc.perform(multipart("/api/v1/members/me/blog-links").file(new MockMultipartFile("logo0","logo.svg","image/svg+xml","<svg><script>evil()</script></svg>".getBytes())).param("links","{\"githubUrl\":\"https://github.com/notitle12\",\"sites\":[{\"label\":\"Site\",\"url\":\"https://example.test\"}]}").header("Authorization",bearer(owner)).with(r->{r.setMethod("PUT");return r;})).andExpect(status().isUnsupportedMediaType());
  mvc.perform(get(path()+"/links")).andExpect(jsonPath("$.data.githubUrl").isEmpty()).andExpect(jsonPath("$.data.sites.length()").value(0));
 }
 @Test void blogSearchNeverLeaksOtherAuthorsOrHiddenPosts()throws Exception{
  createPost(owner,"검색88 공개","PUBLIC");createPost(owner,"검색88 비공개","HIDDEN");createPost(other,"검색88 다른작성자","PUBLIC");
  mvc.perform(get(path()+"/posts").param("q","검색88")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].title").value("검색88 공개"));
  mvc.perform(get(path()+"/posts").param("q","x","y")).andExpect(status().isBadRequest());
  mvc.perform(get(path()+"/posts").param("q","x".repeat(201))).andExpect(status().isBadRequest());
 }
 private void createPost(Member member,String title,String visibility)throws Exception{mvc.perform(post("/api/v1/posts").header("Authorization",bearer(member)).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("title",title,"visibilityStatus",visibility,"tagIds",java.util.List.of(),"blocks",java.util.List.of(java.util.Map.of("type","TEXT","content","검색88 본문")))))).andExpect(status().isCreated());}
 private String bearer(Member member){return "Bearer "+tokens.issueForMember(member.getId(),Instant.now());}
 private String path(){return "/api/v1/blogs/"+owner.getHandle();}
 private Member blog(){String key=UUID.randomUUID().toString().substring(0,12);var member=new Member("writer-"+key,null,MemberStatus.ACTIVE,null,null);member.completeProfile("블로그-"+key,"blog-"+key,member.getNickname(),Instant.now());return members.saveAndFlush(member);}
}
