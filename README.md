# Simple CLI torrent client based on [Bt](https://github.com/atomashpolskiy/bt) library

```java
$ git clone https://github.com/atomashpolskiy/bt
$ cd bt
$ mvn clean package -DskipTests
$ java -jar target/bt-launcher.jar

Option (* = required)  Description                                             
---------------------  -----------                                             
-?, -h, --help                                                                 
-S, --sequential       Download sequentially                                   
-a, --all              Download all files (file selection will be disabled)    
* -d, --dir <File>     Target download location                                
--dhtport <Integer>    Listen on specific port for DHT messages                
-e, --encrypted        Enforce encryption for all connections                  
-f, --file <File>      Torrent metainfo file                                   
-i, --inetaddr         Use specific network address (possible values include IP
                         address literal or hostname)                          
-m, --magnet           Magnet URI                                              
-p, --port <Integer>   Listen on specific port for incoming connections        
-s, --seed             Continue to seed when download is complete              
--trace                Enable trace logging                                    
-v, --verbose          Enable more verbose logging 
```