CREATE TABLE snippets (
                          id          UUID          PRIMARY KEY,
                          name        VARCHAR(255)  NOT NULL,
                          description VARCHAR(1000) NOT NULL,
                          language    VARCHAR(50)   NOT NULL,
                          version     VARCHAR(20)   NOT NULL
);