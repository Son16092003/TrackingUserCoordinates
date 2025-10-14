// supabase.js

const SUPABASE_URL = "https://piggzhqyiewotlhzjhcm.supabase.co";
const SUPABASE_KEY =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBpZ2d6aHF5aWV3b3RsaHpqaGNtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTgxMjQ1MzIsImV4cCI6MjA3MzcwMDUzMn0.Yyq_gJY4J2qhPbIZPe_zXOFvQswt7-GnVD8MOJ-asW8";

// Dùng object supabase từ CDN
export const client = supabase.createClient(SUPABASE_URL, SUPABASE_KEY);
