package com.aiops.backend.service;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Conservative boundary applied independently of the model and to manual writes. */
public final class MemorySafety {
  public static final Set<String> CATEGORIES=Set.of("USER_PREFERENCE","WORKFLOW","ENVIRONMENT","CONFIRMED_EXPERIENCE");
  private static final Pattern SECRET=Pattern.compile("(?is)bearer\\s+|eyJ[A-Za-z0-9_-]{8,}\\.|sk-[A-Za-z0-9_-]{12,}|-----BEGIN|(?:password|passwd|api[_ -]?key|secret|access[_ -]?token|token|jwt|密码|密钥|令牌)\\s*(?:[:=：是为]|\\bis\\b)\\s*\\S+|(?:Traceback|Exception in thread)|(?:同事|他人的|客户的|other user's)");
  private static final Pattern VOLATILE=Pattern.compile("(?i)当前|此刻|刚刚|最近\\s*\\d+\\s*分钟|采样|利用率|CPU使用率|\\bpid\\b|last heartbeat|(?:CPU|GPU|RAM|内存|显存|温度|IO).{0,12}(?:占用|使用|空闲|剩余|负载|可用|为|是).{0,8}\\d+(?:\\.\\d+)?\\s*(?:%|℃|GiB|GB|MiB|MB|字节)");
  public static boolean safe(String text) { return text!=null && !SECRET.matcher(text).find() && text.chars().noneMatch(c->c<32 && c!='\n' && c!='\t'); }
  public static boolean durable(String text) { return safe(text) && !VOLATILE.matcher(text).find(); }
  public static void require(String category,String content) {
    if(!CATEGORIES.contains(category) || content==null || content.isBlank() || content.length()>4000 || !durable(content))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"记忆类别或内容无效，请移除凭据、敏感日志和第三方私人信息");
  }
}
