-- Выполнить под postgres один раз. Схему создаст Flyway при первом старте приложения.
create role survey_api login password 'REPLACE_DB_PASSWORD';
create database survey owner survey_api encoding 'UTF8';
-- при необходимости: grant connect on database survey to survey_api;
