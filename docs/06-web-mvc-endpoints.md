# 6. Web MVC and Endpoints - Kiwi Server

## Spring MVC Controllers

### Controller Advice

### Functional Endpoints

## Generic Query- and Command-Endpoints

### Generic Entity Query

URL parameters:

- The `_fieldset` parameter determines the fieldset to be used, corresponding to a named fieldset property of the
  corresponding FieldsetsAvailable class for the queried entity type. If this parameter is omitted, the "default"
  fieldset for the given entity will be used.
- The `_fields` parameter can be used to specify a dynamic fieldset using field names.
  See ["Chapter 8: "Dynamic Fieldsets"](#dynamic-fieldsets) for the syntax of the dynamic fieldset parameter.
- The `_page`, `_size`, and `_sort` parameters determine the pagination and ordering of entities.
  See the Spring Web MVC documentation
  on [PageableHandlerMethodArgumentResolver](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/web/PageableHandlerMethodArgumentResolver.html)
  and [SortHandlerMethodArgumentResolver](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/web/SortHandlerMethodArgumentResolver.html)
  for details on pagination- and sorting-parameters.
- Other parameters will be used to set entity- and column-filters with corresponding names and operators.

### Dynamic Fieldsets

### Analytical Report Query

---

Next up, [Chapter 7: "Testing and Frontend Schema Generation"](./07-testing.md) is about defining unit- and integration
tests, and automatically generating Typescript fieldset-sources for frontend integration.
