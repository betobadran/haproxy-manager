-- Preferências de exibição por usuário (idioma, tema, formato de data/hora, fuso horário), em JSON
ALTER TABLE users ADD COLUMN prefs TEXT;
