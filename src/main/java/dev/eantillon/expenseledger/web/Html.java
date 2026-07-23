package dev.eantillon.expenseledger.web;

final class Html {

    private Html() {
    }

    static String page(String title, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>%s · Expense Ledger</title>
                  <style>
                    :root{color-scheme:light;--ink:#17201c;--muted:#65706a;--line:#dce4df;
                    --paper:#fff;--wash:#f4f7f5;--accent:#176b4d;--danger:#9d2d25}
                    *{box-sizing:border-box}body{margin:0;background:var(--wash);color:var(--ink);
                    font:15px/1.5 system-ui,sans-serif}main{width:min(1100px,calc(100%% - 32px));margin:32px auto}
                    header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:24px}
                    h1,h2{line-height:1.2}h1{font-size:28px}h2{font-size:18px;margin-top:0}
                    .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}
                    .card{background:var(--paper);border:1px solid var(--line);border-radius:12px;padding:18px;
                    box-shadow:0 3px 14px rgb(25 48 38/5%%)}.muted{color:var(--muted)}.error{color:var(--danger)}
                    table{width:100%%;border-collapse:collapse}th,td{text-align:left;padding:9px 8px;
                    border-bottom:1px solid var(--line);vertical-align:top}th{color:var(--muted);font-size:12px;
                    text-transform:uppercase;letter-spacing:.04em}.scroll{overflow:auto}
                    label{display:block;font-weight:600;margin:12px 0 4px}input,select,textarea{width:100%%;
                    padding:9px 10px;border:1px solid #bac7c0;border-radius:7px;background:#fff;color:var(--ink)}
                    textarea{min-height:90px;resize:vertical}.actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:14px}
                    button,.button{appearance:none;border:0;border-radius:7px;padding:9px 13px;background:var(--accent);
                    color:#fff;font-weight:650;text-decoration:none;cursor:pointer}.secondary{background:#53625b}
                    .danger{background:var(--danger)}code{overflow-wrap:anywhere;font-size:12px}
                    .login{width:min(420px,calc(100%% - 32px));margin:12vh auto}
                  </style>
                </head>
                <body><main>%s</main></body>
                </html>
                """.formatted(escape(title), body);
    }

    static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(Character.toString(34), "&quot;")
                .replace("'", "&#39;");
    }
}
