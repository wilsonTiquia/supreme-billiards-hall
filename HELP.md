# Read Me First
The following was discovered as part of building this project:

* The original package name 'com.supremebilliardshall.billiards-hall-system' is invalid and this project uses 'com.supremebilliardshall.billiards_hall_system' instead.

# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.0.1/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.0.1/maven-plugin/build-image.html)
* [Spring Boot DevTools](https://docs.spring.io/spring-boot/4.0.1/reference/using/devtools.html)
* [Spring Web](https://docs.spring.io/spring-boot/4.0.1/reference/web/servlet.html)
* [Spring Security](https://docs.spring.io/spring-boot/4.0.1/reference/web/spring-security.html)
* [Spring Data JPA](https://docs.spring.io/spring-boot/4.0.1/reference/data/sql.html#data.sql.jpa-and-spring-data)

### Guides
The following guides illustrate how to use some features concretely:

* [Accessing data with MySQL](https://spring.io/guides/gs/accessing-data-mysql/)
* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)
* [Securing a Web Application](https://spring.io/guides/gs/securing-web/)
* [Spring Boot and OAuth2](https://spring.io/guides/tutorials/spring-boot-oauth2/)
* [Authenticating a User with LDAP](https://spring.io/guides/gs/authenticating-ldap/)
* [Accessing Data with JPA](https://spring.io/guides/gs/accessing-data-jpa/)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.


---

# Launch-day: setting the first login credentials

The seeded `owner` and `counter` logins are **disabled** (migration
`V9__disable_seed_credentials.sql` writes a non-bcrypt sentinel, so no password can
authenticate them). Establish real credentials once, at install, like this:

1. **Set the bootstrap variable** in the environment (never in a file, never committed):

   ```
   export SUPREME_BOOTSTRAP_ADMIN_PASSWORD='a-strong-owner-password'
   ```

2. **Start the app.** On boot, `AdminPasswordBootstrap` encodes that value with BCrypt and
   stores it as the `owner` password — but only while `owner` still carries the sentinel, so
   it runs exactly once and never overwrites a password already set. It logs at INFO that it
   happened; the value itself is never logged.

3. **Log in as `owner`.**

4. **Immediately change the password** via `PUT /api/v1/auth/password`
   (`{ "currentPassword": "...", "newPassword": "..." }`), then **unset
   `SUPREME_BOOTSTRAP_ADMIN_PASSWORD`** before the next restart.

5. **Set the counter's password** via `PUT /api/v1/users/{counterUserId}/password`
   (`{ "newPassword": "..." }`). The `counter` login stays disabled until you do this — that
   is intended, not a gap.

Passwords must be at least 8 characters. There are no other composition rules.
