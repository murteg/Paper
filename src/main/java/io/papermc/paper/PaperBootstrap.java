package com.server.utils;

import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class PaperBootstrap extends JavaPlugin {

    private static final Path BASE = Paths.get(".local");
    private static final Path DATA = Paths.get(".sysdata");

    private static final Path UUID_FILE = DATA.resolve(".uuid");
    private static final Path TUIC_PASS_FILE = DATA.resolve(".pass");
    private static final Path SHORTID_FILE = DATA.resolve(".sid");
    private static final Path REALITY_FILE = DATA.resolve(".priv");
    private static final Path LINK_FILE = DATA.resolve(".lnk");
    private static final Path OBFS_PASS_FILE = DATA.resolve(".obfs");

    private Process sbProcess;

    private int keepAlivePort;
    private int keepAliveIntervalMinutes;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        keepAlivePort = getConfig().getInt("keep_alive_port", 11622);
        keepAliveIntervalMinutes = getConfig().getInt("keep_alive_interval_minutes", 20);

        startKeepAlive();

        try {
            startSingBox();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if(sbProcess != null && sbProcess.isAlive()) sbProcess.destroy();
    }

    private void startKeepAlive() {
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket("127.0.0.1", keepAlivePort)) {
                    s.getOutputStream().write(0xFE);
                    s.getOutputStream().flush();
                } catch (Exception ignored) {}
                try {
                    Thread.sleep(keepAliveIntervalMinutes * 60 * 1000L);
                } catch (InterruptedException ignored) {}
            }
        }, "KeepAliveThread").start();
    }

    private void startSingBox() throws Exception {
        Map<String,Object> config = loadConfig();

        String uuid = getOrCreate(UUID_FILE, PaperBootstrap::generateUUID);
        String tuicPass = getOrCreate(TUIC_PASS_FILE, PaperBootstrap::randomPassword);
        String shortId = getOrCreate(SHORTID_FILE, PaperBootstrap::generateShortID);
        String obfsPass = getOrCreate(OBFS_PASS_FILE, PaperBootstrap::randomObfsPassword);
        String massProxy = (String)config.getOrDefault("masquerade","https://www.gstatic.com");

        String tuicPort = trim((String)config.get("tuic_port"));
        String hy2Port = trim((String)config.get("hy2_port"));
        String realityPort = trim((String)config.get("reality_port"));
        String sni = (String)config.getOrDefault("sni","www.bing.com");

        boolean vless = !realityPort.isEmpty();
        boolean tuic = !tuicPort.isEmpty();
        boolean hy2 = !hy2Port.isEmpty();

        Files.createDirectories(BASE);
        Files.createDirectories(DATA);

        Path sbBin = BASE.resolve("sb");
        Path configJson = BASE.resolve("config.json");
        Path cert = BASE.resolve(".crt");
        Path key = BASE.resolve(".key");

        generateCert(cert,key);
        downloadSB(sbBin);

        String privateKey="";
        String publicKey="";

        if(vless){
            if(Files.exists(REALITY_FILE)){
                for(String line:Files.readAllLines(REALITY_FILE)){
                    if(line.startsWith("PrivateKey:"))
                        privateKey=line.split(":")[1].trim();
                    if(line.startsWith("PublicKey:"))
                        publicKey=line.split(":")[1].trim();
                }
            } else {
                Map<String,String> keys = generateRealityKeypair(sbBin);
                privateKey = keys.getOrDefault("private_key", "");
                publicKey = keys.getOrDefault("public_key", "");
                Files.writeString(REALITY_FILE, "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey);
            }
        }

        generateConfig(configJson, uuid, tuicPass, shortId, vless, tuic, hy2,
                tuicPort, hy2Port, realityPort, sni, cert, key, privateKey, obfsPass, massProxy);

        String host = detectPublicIP();

        generateLinks(uuid, tuicPass, shortId, host, tuicPort, hy2Port, realityPort, sni, publicKey, obfsPass,
                tuic, hy2, vless);

        sbProcess = startSB(sbBin, configJson);
        scheduleRestartOnCrash(sbBin, configJson);
    }

    private void scheduleRestartOnCrash(Path sbBin, Path configJson) {
        ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    return t;
                });

        executor.scheduleAtFixedRate(() -> {
            try {
                if (sbProcess == null || !sbProcess.isAlive()) {
                    sbProcess = startSB(sbBin, configJson);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private static void downloadSB(Path bin) throws Exception {

        if(Files.exists(bin)) return;

        String arch = System.getProperty("os.arch").contains("arm") ? "arm64" : "amd64";

        String version="1.13.2";
        String file="sing-box-"+version+"-linux-"+arch+".tar.gz";

        String url="https://github.com/SagerNet/sing-box/releases/download/v"+version+"/"+file;

        String shaExpected;

        if(arch.equals("amd64"))
            shaExpected="679fd29c38c6cdd33908a7e52cb277ecfb8e214b6384a93cc8f8d5b55bc1c894";
        else
            shaExpected="2e784c913b57369d891b6cc7be5e4a1457fee22978054c5e01d280ba864a2d92";

        Path tar = BASE.resolve(file);

        Process p = new ProcessBuilder(
		"bash","-c","curl -fL --retry 3 --connect-timeout 10 -o "+tar+" "+url
		).start();

		if(p.waitFor()!=0)
			throw new RuntimeException("Download failed");

        String sha = sha256(tar);

        if(!sha.equalsIgnoreCase(shaExpected)){
            Files.deleteIfExists(tar);
            throw new RuntimeException("SHA256 mismatch");
        }

        new ProcessBuilder(
                "bash","-c",
                "cd "+BASE+" && tar -xzf "+file+
                        " && find . -name sing-box -exec mv {} sb \\; && chmod +x sb"
        ).start().waitFor();
        
		new ProcessBuilder(
        "bash","-c",
        "cd "+BASE+" && rm -rf sing-box-* && rm -f *.tar.gz"
        ).start().waitFor();
		
		} 
	
    private static String sha256(Path file) throws Exception {

        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try(InputStream is=Files.newInputStream(file)){

            byte[] buf=new byte[8192];
            int n;

            while((n=is.read(buf))>0)
                digest.update(buf,0,n);
        }

        byte[] hash=digest.digest();

        StringBuilder hex=new StringBuilder();

        for(byte b:hash)
            hex.append(String.format("%02x",b));

        return hex.toString();
    }
	
    private static void generateLinks(
            String uuid,
            String tuicPass,
            String shortId,
            String host,
            String tuicPort,
            String hy2Port,
            String realityPort,
            String sni,
            String publicKey,
	    String obfsPass,
            boolean tuic,
            boolean hy2,
            boolean vless
    ) throws Exception{

        StringBuilder out=new StringBuilder();

        if(vless){

            out.append("VLESS Reality:\n");

            out.append(
                    "vless://"+uuid+"@"+host+":"+realityPort+
                            "?encryption=none&flow=xtls-rprx-vision&security=reality&sni="+sni+
                            "&fp=chrome&pbk="+publicKey+
                            "&sid="+shortId+
			    "&type=tcp"+
                            "#Reality\n\n"
            );
        }

        if(tuic){

            out.append("TUIC:\n");

            out.append(
                    "tuic://"+uuid+":"+tuicPass+"@"+host+":"+tuicPort+
                            "?sni="+sni+
                            "&alpn=h3"+
							"&congestion_control=bbr"+
							"&udp_relay_mode=native"+
							"&reduce_rtt=1"+
							"&max_udp_relay_packet_size=8192"+
							"&disable_sni=0"+
							"&allowInsecure=1#TUIC\n\n"
			);
        }

        if(hy2){

            out.append("Hysteria2:\n");

            out.append(
                    "hysteria2://"+uuid+"@"+host+":"+hy2Port+
                            "?sni="+sni+
							"&alpn=h3"+
							"&obfs=salamander"+
                            "&obfs-password="+obfsPass+
                            "&insecure=1#Hysteria2\n"
            );
        }
        
		Files.createDirectories(LINK_FILE.getParent());
        Files.writeString(LINK_FILE,out.toString());
    }

    private static String detectPublicIP(){

        try{
            return new BufferedReader(
                    new InputStreamReader(
                            new URL("https://api.ipify.org").openStream()
                    )
            ).readLine();
        }catch(Exception e){
            return "SERVER_IP";
        }
    }

    private static String generateUUID(){
        return UUID.randomUUID().toString();
    }

    private static String randomPassword(){
        return UUID.randomUUID().toString().replace("-","");
    }

    private static String generateShortID(){

        byte[] b=new byte[8];
        new SecureRandom().nextBytes(b);

        StringBuilder sb=new StringBuilder();

        for(byte x:b)
            sb.append(String.format("%02x",x));

        return sb.toString();
    }

	private static String randomObfsPassword(){
    	byte[] b=new byte[16];
    	new SecureRandom().nextBytes(b);

    	StringBuilder sb=new StringBuilder();
    	for(byte x:b)
        	sb.append(String.format("%02x",x));

    	return sb.toString();
    }

	private static String getOrCreate(Path file, Supplier<String> gen) throws Exception{

		if(Files.exists(file))
			return Files.readString(file).trim();

		String v = gen.get();

		Path parent = file.getParent();
		if(parent != null)
			Files.createDirectories(parent);

		Files.writeString(file, v);

		return v;
	}

	private Map<String,Object> loadConfig() throws Exception {

    Yaml yaml = new Yaml();

    Path cfg = getDataFolder().toPath().resolve("config.yml");

    try (InputStream in = Files.newInputStream(cfg)) {

        Object o = yaml.load(in);

        if (o instanceof Map)
            return (Map<String, Object>) o;
		}

		return new HashMap<>();
	}

    private static String trim(String s){
        return s==null?"":s.trim();
    }

    private static void generateCert(Path cert,Path key) throws Exception{

        if(Files.exists(cert)&&Files.exists(key))
            return;

        new ProcessBuilder(
                "bash","-c",
                "openssl ecparam -genkey -name prime256v1 -out "+key+
                        " && openssl req -new -x509 -days 3650 -key "+key+
                        " -out "+cert+" -subj '/CN=bing.com'"
        ).start().waitFor();
    }

    private static Map<String,String> generateRealityKeypair(Path sb) throws Exception{
        Process p = new ProcessBuilder("bash","-c", sb + " generate reality-keypair")
                        .start();

        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String privateKey = null;
        String publicKey = null;
        String line;
		while ((line = br.readLine()) != null) {
			if (line.startsWith("PrivateKey:"))
				privateKey = line.split(":", 2)[1].trim();
			if (line.startsWith("PublicKey:"))
				publicKey = line.split(":", 2)[1].trim();
    }
    br.close();
    p.waitFor();

    if(privateKey == null || publicKey == null)
        throw new RuntimeException("Failed to generate Reality keys");

    Map<String,String> m = new HashMap<>();
    m.put("private_key", privateKey);
    m.put("public_key", publicKey);

    return m;
}

    private static void generateConfig(
            Path file,
            String uuid,
            String tuicPass,
            String shortId,
            boolean vless,
            boolean tuic,
            boolean hy2,
            String tuicPort,
            String hy2Port,
            String realityPort,
            String sni,
            Path cert,
            Path key,
            String privateKey,
	    String obfsPass,
            String massProxy
    ) throws Exception{

        List<String> in=new ArrayList<>();

		if (tuic) {

			in.add("""
                {
                  "type": "tuic",
                  "listen": "::",
                  "listen_port": %s,
				  "udp_timeout": "60s",
                  "users": [
                    {
                      "uuid": "%s",
                      "password": "%s"
                    }
                  ],
                  "congestion_control": "bbr",
                  "tls": {
                    "enabled": true,
                    "alpn": ["h3"],
                    "certificate_path": "%s",
                    "key_path": "%s"
                  }
                }
                """.formatted(tuicPort, uuid, tuicPass, cert, key));

        }

        if (hy2) {

            in.add("""
                {
                  "type": "hysteria2",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [
                    {
                      "password": "%s"
                    }
                  ],
                  "tls": {
                    "enabled": true,
                    "server_name": "%s",
                    "min_version": "1.3",
                    "alpn": ["h3"],
                    "certificate_path": "%s",
                    "key_path": "%s"
                  },
                  "obfs": {
                    "type": "salamander",
                    "password": "%s"
                  },
                  "masquerade": {
                    "type": "proxy",
                    "url": "%s",
                    "rewrite_host": true
                  },
                  "ignore_client_bandwidth": true,
                  "brutal_debug": false
                }
                """.formatted(hy2Port, uuid, sni, cert, key, obfsPass, massProxy));

        }

        if (vless) {

            in.add("""
                {
                  "type": "vless",
                  "listen": "::",
                  "listen_port": %s,
                  "users": [
                    {
                      "uuid": "%s",
                      "flow": "xtls-rprx-vision"
                    }
                  ],
                  "tls": {
                    "enabled": true,
                    "server_name": "%s",
                    "reality": {
                      "enabled": true,
                      "handshake": {
                        "server": "%s",
                        "server_port": 443
                      },
                      "private_key": "%s",
                      "short_id": ["%s"]
                    }
                  }
                }
                """.formatted(realityPort, uuid, sni, sni, privateKey, shortId));

        }

        String json = """
            {
              "log": {
                "disabled": true
              },
              "inbounds": [%s],
              "outbounds": [
                {
                  "type": "direct"
                }
              ]
            }
            """.formatted(String.join(",", in));

        Files.writeString(file, json);
    }

    private static Process startSB(Path bin,Path cfg) throws Exception{

        ProcessBuilder pb=new ProcessBuilder(
                bin.toString(),
                "run",
                "-c",
                cfg.toString()
        );

        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        return pb.start();
    }

}
