package dev.hapm.manager.web;

import dev.hapm.manager.security.SessionUser;
import dev.hapm.manager.service.AwsClients;
import dev.hapm.manager.store.Store;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Teste do acesso do manager à AWS (credenciais / role assumida). Somente ADMIN. */
@RestController
public class AwsController {
  private final Store store;
  private final AwsClients aws;

  public AwsController(Store store, AwsClients aws) {
    this.store = store;
    this.aws = aws;
  }

  @PostMapping("/api/clusters/{cid}/aws/test")
  public AwsClients.TestResult test(@PathVariable String cid, @AuthenticationPrincipal SessionUser user) {
    var c = store.getCluster(cid).orElseThrow(() -> Http.notFound("cluster não encontrado"));
    aws.forget(cid);
    var r = aws.test(c, user.username());
    store.audit(user.username(), cid, "aws.test", r.identity(), java.util.Map.of("ok", r.ok()));
    return r;
  }
}
