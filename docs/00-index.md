# Kiwi Server Documentation

Welcome to the Kiwi Framework.

<img src="../kiwi.svg" height="120" alt="Kiwi Logo">

This **K**otlin **I**nfrastructure for **W**eb **I**nnovation project aims to support fast, reliable, and maintainable
development of Web-Application Servers, based on the [Jakarta](https://jakarta.ee/)
and [Spring Framework](https://spring.io/projects/spring-framework) ecosystem, using
the [Kotlin](https://kotlinlang.org/) programming language.

This guide explains the architecture, core concepts, and usage of the Kiwi Server-Application Framework.  
Alongside explaining concepts, this guide shows how to build an example Todo-List web-server application for you to
follow along.

## Table of Contents

1. [Introduction](./01-introduction.md): Requirements, framework modules, application architecture, and setting up a
   new project
2. [Domain Entities and Schema](./02-domain-schema.md): Defining domain entities, database migrations, entity schemas,
   fieldsets, and validation
3. [Application Commands, Queries and Events](./03-application-command-query.md): Defining application use-cases with
   commands, queries and events, their handlers, and the command-, query- and event-bus
4. [Database Querying and Access-Control](./04-querying-access-control.md): How database querying works using query
   schemas, filters and analytical reports, and how to define access-control and field-access-control
5. [Infrastructure and Integration](./05-infrastructure-integration.md): Auto-configuration and configuration
   properties, Spring Security integration, e-mails, file storage, and the audit-log
6. [Web MVC and Endpoints](./06-web-mvc-endpoints.md): Spring Web MVC controllers, exception handling, generic entity
   and report endpoints, and OpenAPI documentation
7. [Testing and Frontend Code-Generation](./07-testing.md): Unit-, slice- and integration-testing the application,
   command- and query-verification, and generating fieldset sources for frontends
8. [Advanced Features](./08-advanced.md): In-depth topics, elaborating on the internals and more advanced features
9. [Troubleshooting](./09-troubleshooting.md): Resolving known issues in development and deployment
10. [Migrating to Kiwi 1.5](./98-migration-1.5.md): Changes required when upgrading an application from Kiwi 1.4
11. [Appendix](./99-appendix.md): Glossary, Snowflake IDs, diagrams, and the example application project

---

&copy; werdenktwas GmbH, 2024-2026  
Alexander Dahmen [&lt;dahmen@werdenktwas.de&gt;](mailto:dahmen@werdenktwas.de)
