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

# Going live: choosing the first passwords

`scripts/reset-for-golive.sh` wipes the database to a clean hall and sets the owner and counter
passwords. It has two paths:

- **Interactive (recommended, the default):** run it with no flag and it asks you to type the
  two real passwords — no default password ever exists, so there is nothing to leak or forget
  to change.
- **`--default-password`:** it seeds one documented default (`supreme-golive`) for both
  accounts and forces each to change it on first sign-in — convenient for a hands-off install,
  at the cost of a default briefly existing (usable only to change itself, nothing else).

Either way the script keeps its safety catch (it refuses to run against a database with
payments unless you type the exact `DESTROY N PAYMENTS` phrase) and writes the `.golive` marker.

If you are **not** wiping the database — a fresh checkout, or recovery — use the bootstrap
variable below instead.

---

# Launch-day: setting the first login credentials (without wiping)

The seeded `owner` and `counter` logins are **disabled** (migration
`V9__disable_seed_credentials.sql` writes a non-bcrypt sentinel, so no password can
authenticate them). When you are not running the go-live reset above, establish credentials
once, at install, like this:

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

---

# Recovering a forgotten owner password (break-glass)

There is **no in-app reset for the owner**, on purpose: no admin can reset another admin, so
one admin cannot take over another. If the owner password is lost, recover it at the database.
This needs direct database access **by design** — it is the one path that cannot be reached
from the network, which is exactly why it is safe to keep the owner un-resettable online.

Run this on the database host. Substitute a real password for the placeholder.

1. **Disable the owner login** by writing the sentinel back over its password hash:

   ```
   psql "$DB_URL" -c "UPDATE app_user SET password_hash = 'DISABLED-NO-LOGIN' WHERE username = 'owner';"
   ```

   (If you connect some other way, run just the SQL: `UPDATE app_user SET password_hash =
   'DISABLED-NO-LOGIN' WHERE username = 'owner';`)

2. **Set the bootstrap variable** to the new owner password, in the app's environment:

   ```
   export SUPREME_BOOTSTRAP_ADMIN_PASSWORD='a-new-strong-owner-password'
   ```

3. **Restart the app.** On boot it sees the sentinel, encodes the variable with BCrypt, stores
   it as the owner password, and logs at INFO that it did so (never the value).

4. **Log in as `owner`** with that password.

5. **Change it immediately** via `PUT /api/v1/auth/password`, then **unset
   `SUPREME_BOOTSTRAP_ADMIN_PASSWORD`** before the next restart so it cannot fire again.

Step 1 matters: the bootstrap only acts while the account carries the sentinel, so a real
(forgotten) hash must be reset to the sentinel first, or nothing happens on restart.

## This procedure only rescues `owner`

**It is hardcoded.** `AdminPasswordBootstrap` looks up the username `owner` and no other, so the
steps above do nothing for an administrator added through **Admin → Staff**. Two things follow.

**A forgotten administrator password is not something the other administrator can fix.** No admin
can reset another admin's password — that rule is deliberate, and it is why one admin cannot take
over another's account. A second administrator covers **lockouts** (five bad passwords at the
till), not **amnesia**. They are different failures with different answers:

| What happened | The way back |
|---|---|
| Somebody is locked out after bad passwords | Any other administrator: **Admin → Staff**, or `DELETE /api/v1/users/lockouts` |
| A **non-`owner`** administrator forgot their password | Another administrator archives that account and adds a replacement. The username is freed by archiving, so the same one can be used again. |
| **`owner`** forgot their password | The break-glass procedure above |
| The **last** administrator forgot their password, and it is not `owner` | **Nothing in the app, and the procedure above will not help.** See below. |

**Keep the `owner` account.** It is the only account the break-glass procedure can reach, so it is
the hall's last way in. Archiving it is allowed once a second administrator exists — the
last-administrator guard only counts how many remain — but doing so removes the only recoverable
account. If `owner` is archived and the remaining administrator forgets their password, there is
no route back in short of editing the database by hand.

If the hall ever wants to retire `owner`, the bootstrap needs to take a username too (a
`SUPREME_BOOTSTRAP_ADMIN_USERNAME` beside the password) before that is safe. It does not today.
