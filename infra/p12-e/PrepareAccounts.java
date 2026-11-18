import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.infrastructure.InitialAdmin;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** One-shot, local-only account preparation. No product HTTP test endpoint or business fixture. */
public final class PrepareAccounts {
  public static void main(String[] args) throws Exception {
    var app = new SpringApplication(MarketplaceApplication.class);
    try (var context = app.run("--spring.profiles.active=db,local-mailbox,local-http",
        "--server.port=0", "--marketplace.runtime.mode=local-full", "--marketplace.phone.mode=disabled")) {
      var signup = context.getBean(SignupOperations.class);
      var mailbox = context.getBean(LocalTestMailbox.class);
      var jdbc = context.getBean(NamedParameterJdbcTemplate.class);
      if (jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user", Map.of(), Integer.class) != 0)
        throw new IllegalStateException("Fresh database required");
      var credentials = new LinkedHashMap<String,Object>();
      for (String project : List.of("desktop", "mobile")) {
        var actors = new LinkedHashMap<String,Object>();
        for (String role : List.of("buyer", "seller", "admin")) {
          String email = "p12e-" + role + "-" + project + "@example.com";
          String password = "P12E!" + UUID.randomUUID() + "individual";
          UUID id = signup.register(email,password);
          signup.verifyEmail(mailbox.messages().stream().filter(m -> m.recipient().equals(email)).findFirst().orElseThrow().token());
          // Only one initial operator; seller authority is subsequently earned by real application/decision UI.
          if (role.equals("admin") && project.equals("desktop"))
            new InitialAdmin(jdbc,context.getBean(PlatformTransactionManager.class)).initialize(id,System.getenv("EXPECTED_DATABASE"),"INITIAL_ADMIN");
          actors.put(role,Map.of("email",email,"password",password,"id",id));
        }
        credentials.put(project,actors);
      }
      jdbc.update("DELETE FROM marketplace.spring_session",Map.of());
      if (jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order",Map.of(),Integer.class) != 0)
        throw new IllegalStateException("Business data must be produced by browser/API commands");
      var json = tools.jackson.databind.json.JsonMapper.builder().build();
      Files.writeString(Path.of("/output/accounts.private.json"),json.writeValueAsString(credentials),StandardOpenOption.CREATE_NEW);
      Files.writeString(Path.of("/output/preparation.json"),json.writeValueAsString(Map.of("users",6,"initialAdmin",1,"orders",0,"sellerGrants",0,"mailbox","local in-process; consumed before public start")),StandardOpenOption.CREATE_NEW);
    }
  }
}
