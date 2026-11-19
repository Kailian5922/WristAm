// 从鸿蒙版 ArtistAliasTable.ets 自动提取的「本地名 → 目录规范名」对照表。
// 用途：Apple 公开搜索只索引规范名（例：方大同 → Khalil Fong），中文别名命中差。
package com.applemusic.android.apple

import com.applemusic.android.util.ChineseText
import java.text.Normalizer

object ArtistAliasTable {
    private val PAIRS: Array<Array<String>> = arrayOf(
        arrayOf("方大同", "Khalil Fong"),
        arrayOf("周杰伦", "Jay Chou"),
        arrayOf("邓紫棋", "G.E.M."),
        arrayOf("陈奕迅", "Eason Chan"),
        arrayOf("林俊杰", "JJ Lin"),
        arrayOf("王力宏", "Leehom Wang"),
        arrayOf("陶喆", "David Tao"),
        arrayOf("张学友", "Jacky Cheung"),
        arrayOf("刘德华", "Andy Lau"),
        arrayOf("郭富城", "Aaron Kwok"),
        arrayOf("黎明", "Leon Lai"),
        arrayOf("王菲", "Faye Wong"),
        arrayOf("那英", "Na Ying"),
        arrayOf("孙燕姿", "Stefanie Sun"),
        arrayOf("蔡依林", "Jolin Tsai"),
        arrayOf("莫文蔚", "Karen Mok"),
        arrayOf("梁静茹", "Fish Leong"),
        arrayOf("张惠妹", "A-Mei"),
        arrayOf("田馥甄", "Hebe Tien"),
        arrayOf("蔡健雅", "Tanya Chua"),
        arrayOf("陈绮贞", "Cheer Chen"),
        arrayOf("卢广仲", "Crowd Lu"),
        arrayOf("吴青峰", "Wu Qing-Feng"),
        arrayOf("韦礼安", "WeiBird"),
        arrayOf("徐佳莹", "Lala Hsu"),
        arrayOf("邓丽君", "Teresa Teng"),
        arrayOf("张国荣", "Leslie Cheung"),
        arrayOf("梅艳芳", "Anita Mui"),
        arrayOf("罗大佑", "Lo Ta-You"),
        arrayOf("李宗盛", "Jonathan Lee"),
        arrayOf("周华健", "Emil Chau"),
        arrayOf("任贤齐", "Richie Jen"),
        arrayOf("光良", "Michael Wong"),
        arrayOf("品冠", "Victor Wong"),
        arrayOf("张信哲", "Jeff Chang"),
        arrayOf("林宥嘉", "Yoga Lin"),
        arrayOf("萧敬腾", "Jam Hsiao"),
        arrayOf("杨宗纬", "Aska Yang"),
        arrayOf("潘玮柏", "Wilber Pan"),
        arrayOf("罗志祥", "Show Lo"),
        arrayOf("李荣浩", "Li Ronghao"),
        arrayOf("毛不易", "Mao Buyi"),
        arrayOf("薛之谦", "Joker Xue"),
        arrayOf("华晨宇", "Hua Chenyu"),
        arrayOf("张杰", "Jason Zhang"),
        arrayOf("汪峰", "Wang Feng"),
        arrayOf("朴树", "Pu Shu"),
        arrayOf("许嵩", "Vae"),
        arrayOf("汪苏泷", "Silence Wang"),
        arrayOf("周深", "Zhou Shen"),
        arrayOf("李宇春", "Chris Lee"),
        arrayOf("张靓颖", "Jane Zhang"),
        arrayOf("郁可唯", "Yisa Yu"),
        arrayOf("谭维维", "Sitar Tan"),
        arrayOf("刘若英", "Rene Liu"),
        arrayOf("许巍", "Xu Wei"),
        arrayOf("郑钧", "Zheng Jun"),
        arrayOf("朴炯植", "Park Hyung Sik"),
        arrayOf("五月天", "Mayday"),
        arrayOf("苏打绿", "Sodagreen"),
        arrayOf("信乐团", "Shin"),
        arrayOf("动力火车", "Power Station"),
        arrayOf("飞儿乐团", "F.I.R."),
        arrayOf("Beyond", "Beyond"),
        arrayOf("黑豹乐队", "Black Panther"),
        arrayOf("痛仰", "Miserable Faith"),
        arrayOf("新裤子", "New Pants"),
        arrayOf("万能青年旅店", "Omnipotent Youth Society"),
        arrayOf("陈粒", "Chen Li"),
        arrayOf("赵雷", "Zhao Lei"),
        arrayOf("宋冬野", "Song Dongye"),
        arrayOf("李志", "Li Zhi"),
        arrayOf("马頔", "Ma Di"),
        arrayOf("好妹妹", "Hao Mei Mei"),
        arrayOf("逃跑计划", "Escape Plan"),
        arrayOf("鹿先森乐队", "Mr. Deer"),
        arrayOf("王源", "Wang Yuan"),
        arrayOf("王俊凯", "Karry Wang"),
        arrayOf("易烊千玺", "Jackson Yee"),
        arrayOf("蔡徐坤", "KUN"),
        arrayOf("张艺兴", "Lay Zhang"),
        arrayOf("鹿晗", "Lu Han"),
        arrayOf("吴亦凡", "Kris Wu"),
        arrayOf("刘柏辛", "Lexie Liu"),
        arrayOf("袁娅维", "Tia Ray"),
        arrayOf("吉克隽逸", "Summer Jike"),
        arrayOf("阿肆", "A Si"),
        arrayOf("房东的猫", "Landlord Cat"),
        arrayOf("程璧", "Cheng Bi"),
        arrayOf("李健", "Li Jian"),
        arrayOf("孙楠", "Sun Nan"),
        arrayOf("韩红", "Han Hong"),
        arrayOf("腾格尔", "Tengger"),
        arrayOf("腾格尔", "Tengger"),
        arrayOf("降央卓玛", "Jamyang Dolma"),
        arrayOf("痛痒乐队", "Miserable Faith"),
        arrayOf("海龟先生", "Mr. Sea Turtle"),
        arrayOf("二手玫瑰", "Second Hand Rose"),
        arrayOf("C-BLOCK", "C-BLOCK"),
        arrayOf("Higher Brothers", "Higher Brothers"),
        arrayOf("蛋堡", "Soft Lipa"),
        arrayOf("热狗", "MC HotDog"),
        arrayOf("MC HotDog", "MC HotDog"),
        arrayOf("马思唯", "Masiwei"),
        arrayOf("王以太", "Wang Yitai"),
        arrayOf("GAI", "GAI"),
        arrayOf("艾热", "AIR"),
        arrayOf("满舒克", "Manshuke"),
        arrayOf("万妮达", "Vinida"),
        arrayOf("陈慧娴", "Priscilla Chan"),
        arrayOf("林忆莲", "Sandy Lam"),
        arrayOf("叶倩文", "Sally Yeh"),
        arrayOf("郑秀文", "Sammi Cheng"),
        arrayOf("杨千嬅", "Miriam Yeung"),
        arrayOf("容祖儿", "Joey Yung"),
        arrayOf("谢霆锋", "Nicholas Tse"),
        arrayOf("陈慧琳", "Kelly Chen"),
        arrayOf("李克勤", "Hacken Lee"),
        arrayOf("谭咏麟", "Alan Tam"),
        arrayOf("林子祥", "George Lam"),
        arrayOf("许冠杰", "Sam Hui"),
        arrayOf("黄家驹", "Wong Ka Kui"),
        arrayOf("陈百强", "Danny Chan"),
        arrayOf("周慧敏", "Vivian Chow"),
        arrayOf("王杰", "Dave Wang"),
        arrayOf("巫启贤", "Eric Moo"),
        arrayOf("张宇", "Phil Chang"),
        arrayOf("庾澄庆", "Harlem Yu"),
        arrayOf("伍佰", "Wu Bai"),
        arrayOf("齐秦", "Chyi Chin"),
        arrayOf("苏芮", "Julie Sue"),
        arrayOf("蔡琴", "Tsai Chin"),
        arrayOf("费玉清", "Fei Yu-Ching"),
        arrayOf("凤飞飞", "Feng Fei-Fei"),
        arrayOf("甄妮", "Jenny Tseng"),
        arrayOf("娃娃", "Wawa"),
        arrayOf("辛晓琪", "Winnie Hsin"),
        arrayOf("万芳", "Wan Fang"),
        arrayOf("黄韵玲", "Kay Huang"),
        arrayOf("陈升", "Bobby Chen"),
        arrayOf("陶晶莹", "Matilda Tao"),
        arrayOf("黄立行", "Stanley Huang"),
        arrayOf("F4", "F4"),
        arrayOf("飞轮海", "Fahrenheit"),
        arrayOf("S.H.E", "S.H.E"),
        arrayOf("TWINS", "Twins"),
        arrayOf("陈冠希", "Edison Chen"),
        arrayOf("方力申", "Alex Fong"),
        arrayOf("郑中基", "Ronald Cheng"),
        arrayOf("张学友", "Jacky Cheung"),
        arrayOf("侧田", "Justin Lo"),
        arrayOf("卫兰", "Janice Vidal"),
        arrayOf("关淑怡", "Shirley Kwan"),
        arrayOf("彭羚", "Cass Phang"),
        arrayOf("李蕙敏", "Amanda Lee"),
        arrayOf("许志安", "Andy Hui"),
        arrayOf("苏永康", "William So"),
        arrayOf("张敬轩", "Hins Cheung"),
        arrayOf("林一峰", "Chet Lam"),
        arrayOf("at17", "at17"),
        arrayOf("My Little Airport", "My Little Airport"),
        arrayOf("麦浚龙", "Juno Mak"),
        arrayOf("陈奕迅", "Eason Chan"),
        arrayOf("何韵诗", "Denise Ho"),
        arrayOf("黄耀明", "Anthony Wong"),
        arrayOf("卢巧音", "Candy Lo"),
        arrayOf("谢安琪", "Kay Tse"),
        arrayOf("王菀之", "Ivana Wong"),
        arrayOf("吴雨霏", "Kary Ng"),
        arrayOf("周柏豪", "Pakho Chau"),
        arrayOf("邓小巧", "Tang Siu Hau"),
        arrayOf("林二汶", "Eman Lam"),
        arrayOf("岑宁儿", "Yoyo Sham"),
        arrayOf("黄妍", "Cath Wong"),
        arrayOf("Serrini", "Serrini"),
        arrayOf("林家谦", "Terence Lam"),
        arrayOf("椎名林檎", "Sheena Ringo"),
        arrayOf("宇多田光", "Utada"),
        arrayOf("宇多田ヒカル", "Utada"),
        arrayOf("米津玄师", "Kenshi Yonezu"),
        arrayOf("あいみょん", "Aimyon"),
        arrayOf("星野源", "Gen Hoshino"),
        arrayOf("安室奈美惠", "Namie Amuro"),
        arrayOf("滨崎步", "Ayumi Hamasaki"),
        arrayOf("幸田来未", "Koda Kumi"),
        arrayOf("中岛美嘉", "Mika Nakashima"),
        arrayOf("绚香", "Ayaka"),
        arrayOf("生物股长", "Ikimonogakari"),
        arrayOf("藤井风", "Fujii Kaze"),
        arrayOf("山下达郎", "Tatsuro Yamashita"),
        arrayOf("松任谷由实", "Yumi Matsutoya"),
        arrayOf("坂本龙一", "Ryuichi Sakamoto"),
        arrayOf("久石让", "Joe Hisaishi"),
        arrayOf("喜多郎", "Kitaro"),
        arrayOf("平原绫香", "Ayaka Hirahara"),
        arrayOf("美空云雀", "Hibari Misora"),
        arrayOf("五轮真弓", "Mayumi Itsuwa"),
        arrayOf("中森明菜", "Akina Nakamori"),
        arrayOf("松田圣子", "Seiko Matsuda"),
        arrayOf("工藤静香", "Shizuka Kudo"),
        arrayOf("槙原敬之", "Noriyuki Makihara"),
        arrayOf("小田和正", "Kazumasa Oda"),
        arrayOf("桑田佳祐", "Keisuke Kuwata"),
        arrayOf("南方之星", "Southern All Stars"),
        arrayOf("B'z", "B'z"),
        arrayOf("Mr.Children", "Mr.Children"),
        arrayOf("Spitz", "Spitz"),
        arrayOf("柚子", "Yuzu"),
        arrayOf("可苦可乐", "Kobukuro"),
        arrayOf("色情涂鸦", "Porno Graffitti"),
        arrayOf("岚", "ARASHI"),
        arrayOf("关西杰尼斯8", "Kanjani Eight"),
        arrayOf("东京事变", "Tokyo Jihen"),
        arrayOf("X JAPAN", "X JAPAN"),
        arrayOf("彩虹乐团", "L'Arc-en-Ciel"),
        arrayOf("凛冽时雨", "Ling tosite sigure"),
        arrayOf("青苹果", "Blue Hearts"),
        arrayOf("女王蜂", "Ziyoou-vachi"),
        arrayOf("鱼韵", "Sakanaction"),
        arrayOf("欢愉乐团", "King Gnu"),
        arrayOf("胡子男", "Official HIGE DANdism"),
        arrayOf("绿黄色社会", "Ryokuoushoku Shakai"),
        arrayOf("ヨルシカ", "Yorushika"),
        arrayOf("夜好性", "ZUTOMAYO"),
        arrayOf("ずっと真夜中でいいのに。", "ZUTOMAYO"),
        arrayOf("女王", "Queen Bee"),
        arrayOf("爱缪", "Aimyon"),
        arrayOf("凡人谱", "Hitorie"),
        arrayOf("中村佳穗", "Kaho Nakamura"),
        arrayOf("宇多田光", "Utada Hikaru"),
        arrayOf("Reol", "Reol"),
        arrayOf("三浦大知", "Daichi Miura"),
        arrayOf("平原綾香", "Ayaka Hirahara"),
        arrayOf("防弹少年团", "BTS"),
        arrayOf("少女时代", "Girls' Generation"),
        arrayOf("东方神起", "TVXQ!"),
        arrayOf("宝儿", "BoA"),
        arrayOf("李孝利", "Lee Hyori"),
        arrayOf("太妍", "TAEYEON"),
        arrayOf("泰妍", "TAEYEON"),
        arrayOf("白艺潾", "Baek Yerin"),
        arrayOf("李遐怡", "LEE HI"),
        arrayOf("脸红的思春期", "BOL4"),
        arrayOf("金请夏", "CHUNG HA"),
        arrayOf("宣美", "SUNMI"),
        arrayOf("泫雅", "HyunA"),
        arrayOf("华莎", "HWASA"),
        arrayOf("泰民", "TAEMIN"),
        arrayOf("伯贤", "BAEKHYUN"),
        arrayOf("智旻", "Jimin"),
        arrayOf("柾国", "Jung Kook"),
        arrayOf("金泰亨", "V"),
        arrayOf("闵玧其", "SUGA"),
        arrayOf("郑号锡", "j-hope"),
        arrayOf("金南俊", "RM"),
        arrayOf("金硕珍", "Jin"),
        arrayOf("朴智旻", "Jimin"),
        arrayOf("田柾国", "Jung Kook"),
        arrayOf("粉墨", "BLACKPINK"),
        arrayOf("红贝贝", "Red Velvet"),
        arrayOf("妈妈木", "MAMAMOO"),
        arrayOf("十七", "SEVENTEEN"),
        arrayOf("迷孩", "Stray Kids"),
        arrayOf("艾斯帕", "aespa"),
        arrayOf("一击", "ITZY"),
        arrayOf("纽金斯", "NewJeans"),
        arrayOf("乐塞拉芬", "LE SSERAFIM"),
        arrayOf("爱我的", "(G)I-DLE"),
        arrayOf("魑", "IVE"),
        arrayOf("EXO", "EXO"),
        arrayOf("BIGBANG", "BIGBANG"),
        arrayOf("2NE1", "2NE1"),
        arrayOf("SHINee", "SHINee"),
        arrayOf("Super Junior", "Super Junior"),
        arrayOf("WINNER", "WINNER"),
        arrayOf("iKON", "iKON"),
        arrayOf("AKMU", "AKMU"),
        arrayOf("乐童音乐家", "AKMU"),
        arrayOf("싸이", "PSY"),
        arrayOf("鸟叔", "PSY"),
        arrayOf("Epik High", "Epik High"),
        arrayOf("Dynamic Duo", "Dynamic Duo"),
        arrayOf("10cm", "10cm"),
        arrayOf("IU", "IU"),
        arrayOf("아이유", "IU"),
        arrayOf("ZICO", "ZICO"),
        arrayOf("DEAN", "DEAN"),
        arrayOf("Crush", "Crush"),
        arrayOf("Heize", "Heize"),
        arrayOf("脸红的思春期", "Bolbbalgan4"),
        arrayOf("白艺潾", "Baek Yerin"),
        arrayOf("格伦·古尔德", "Glenn Gould"),
        arrayOf("格伦・古尔德", "Glenn Gould"),
        arrayOf("马克思·李希特", "Max Richter"),
        arrayOf("马克思・李希特", "Max Richter"),
        arrayOf("内田光子", "Mitsuko Uchida"),
        arrayOf("马友友", "Yo-Yo Ma"),
        arrayOf("阿图尔·鲁宾斯坦", "Arthur Rubinstein"),
        arrayOf("阿图尔・鲁宾斯坦", "Arthur Rubinstein"),
        arrayOf("阿尔多·奇科里尼", "Aldo Ciccolini"),
        arrayOf("阿尔多 · 奇科里尼", "Aldo Ciccolini"),
        arrayOf("瓦尔特·吉泽金", "Walter Gieseking"),
        arrayOf("瓦尔特 · 吉泽金", "Walter Gieseking"),
        arrayOf("菲利普·格拉斯", "Philip Glass"),
        arrayOf("菲利普・格拉斯", "Philip Glass"),
        arrayOf("赫伯特·冯·卡拉扬", "Herbert von Karajan"),
        arrayOf("卡拉扬", "Herbert von Karajan"),
        arrayOf("柏林爱乐乐团", "Berliner Philharmoniker"),
        arrayOf("维也纳爱乐乐团", "Wiener Philharmoniker"),
        arrayOf("阿姆斯特丹皇家音乐厅管弦乐团", "Royal Concertgebouw Orchestra"),
        arrayOf("贝多芬", "Ludwig van Beethoven"),
        arrayOf("巴赫", "Johann Sebastian Bach"),
        arrayOf("莫扎特", "Wolfgang Amadeus Mozart"),
        arrayOf("肖邦", "Frédéric Chopin"),
        arrayOf("德彪西", "Claude Debussy"),
        arrayOf("拉赫玛尼诺夫", "Sergei Rachmaninoff"),
        arrayOf("柴可夫斯基", "Pyotr Ilyich Tchaikovsky"),
        arrayOf("舒伯特", "Franz Schubert"),
        arrayOf("舒曼", "Robert Schumann"),
        arrayOf("勃拉姆斯", "Johannes Brahms"),
        arrayOf("李斯特", "Franz Liszt"),
        arrayOf("瓦格纳", "Richard Wagner"),
        arrayOf("马勒", "Gustav Mahler"),
        arrayOf("布鲁克纳", "Anton Bruckner"),
        arrayOf("西贝柳斯", "Jean Sibelius"),
        arrayOf("萨蒂", "Erik Satie"),
        arrayOf("拉威尔", "Maurice Ravel"),
        arrayOf("普罗科菲耶夫", "Sergei Prokofiev"),
        arrayOf("肖斯塔科维奇", "Dmitri Shostakovich"),
        arrayOf("斯特拉文斯基", "Igor Stravinsky"),
        arrayOf("门德尔松", "Felix Mendelssohn"),
        arrayOf("亨德尔", "George Frideric Handel"),
        arrayOf("维瓦尔第", "Antonio Vivaldi"),
        arrayOf("帕赫贝尔", "Johann Pachelbel"),
        arrayOf("格里格", "Edvard Grieg"),
        arrayOf("德沃夏克", "Antonín Dvořák"),
        arrayOf("阿尔沃·帕特", "Arvo Pärt"),
        arrayOf("阿尔沃・帕特", "Arvo Pärt"),
        arrayOf("霍洛维茨", "Vladimir Horowitz"),
        arrayOf("里赫特", "Sviatoslav Richter"),
        arrayOf("阿格里奇", "Martha Argerich"),
        arrayOf("郎朗", "Lang Lang"),
        arrayOf("王羽佳", "Yuja Wang"),
        arrayOf("李云迪", "Yundi Li"),
        arrayOf("傅聪", "Fou Ts'ong"),
        arrayOf("郑京和", "Kyung Wha Chung"),
        arrayOf("安妮-索菲·穆特", "Anne-Sophie Mutter"),
        arrayOf("伊扎克·帕尔曼", "Itzhak Perlman"),
        arrayOf("马友友", "Yo-Yo Ma"),
        arrayOf("卡萨尔斯", "Pablo Casals"),
        arrayOf("杜普蕾", "Jacqueline du Pré"),
        arrayOf("比尔·埃文斯", "Bill Evans"),
        arrayOf("比尔・埃文斯", "Bill Evans"),
        arrayOf("迈尔斯·戴维斯", "Miles Davis"),
        arrayOf("迈尔斯・戴维斯", "Miles Davis"),
        arrayOf("切特·贝克", "Chet Baker"),
        arrayOf("切特・贝克", "Chet Baker"),
        arrayOf("凯斯·杰瑞特", "Keith Jarrett"),
        arrayOf("凯斯・杰瑞特", "Keith Jarrett"),
        arrayOf("奥斯卡·彼得森", "Oscar Peterson"),
        arrayOf("奥斯卡・彼得森", "Oscar Peterson"),
        arrayOf("安东尼奥·卡洛斯·若宾", "Antônio Carlos Jobim"),
        arrayOf("若昂·吉尔贝托", "João Gilberto"),
        arrayOf("斯坦·盖茨", "Stan Getz"),
        arrayOf("布拉德·梅尔道", "Brad Mehldau"),
        arrayOf("布拉德・梅尔道", "Brad Mehldau"),
        arrayOf("约翰·柯川", "John Coltrane"),
        arrayOf("埃拉·菲茨杰拉德", "Ella Fitzgerald"),
        arrayOf("路易斯·阿姆斯特朗", "Louis Armstrong"),
        arrayOf("杜克·艾灵顿", "Duke Ellington"),
        arrayOf("赫比·汉考克", "Herbie Hancock"),
        arrayOf("帕特·麦席尼", "Pat Metheny"),
        arrayOf("罗伊·哈格罗夫", "Roy Hargrove"),
        arrayOf("坂本龙一", "Ryuichi Sakamoto"),
        arrayOf("布赖恩·伊诺", "Brian Eno"),
        arrayOf("布莱恩·伊诺", "Brian Eno"),
        arrayOf("布莱恩・伊诺", "Brian Eno"),
        arrayOf("奥拉佛·阿纳尔德斯", "Ólafur Arnalds"),
        arrayOf("奥拉佛・阿纳尔德斯", "Ólafur Arnalds"),
        arrayOf("尼尔斯·弗拉姆", "Nils Frahm"),
        arrayOf("尼尔斯・弗拉姆", "Nils Frahm"),
        arrayOf("哈妮雅·拉尼", "Hania Rani"),
        arrayOf("哈妮雅・拉尼", "Hania Rani"),
        arrayOf("乔珀·贝温", "Joep Beving"),
        arrayOf("乔珀・贝温", "Joep Beving"),
        arrayOf("平克·弗洛伊德", "Pink Floyd"),
        arrayOf("平克・弗洛伊德", "Pink Floyd"),
        arrayOf("披头士", "The Beatles"),
        arrayOf("甲壳虫乐队", "The Beatles"),
        arrayOf("滚石乐队", "The Rolling Stones"),
        arrayOf("皇后乐队", "Queen"),
        arrayOf("齐柏林飞艇", "Led Zeppelin"),
        arrayOf("涅槃乐队", "Nirvana"),
        arrayOf("电台司令", "Radiohead"),
        arrayOf("酷玩乐队", "Coldplay"),
        arrayOf("U2", "U2"),
        arrayOf("鲍勃·迪伦", "Bob Dylan"),
        arrayOf("大卫·鲍伊", "David Bowie"),
        arrayOf("约翰·列侬", "John Lennon"),
        arrayOf("保罗·麦卡特尼", "Paul McCartney"),
        arrayOf("埃里克·克莱普顿", "Eric Clapton"),
        arrayOf("史蒂维·旺德", "Stevie Wonder"),
        arrayOf("迈克尔·杰克逊", "Michael Jackson"),
        arrayOf("麦当娜", "Madonna"),
        arrayOf("泰勒·斯威夫特", "Taylor Swift"),
        arrayOf("碧昂丝", "Beyoncé"),
        arrayOf("阿黛尔", "Adele"),
        arrayOf("比莉·艾利什", "Billie Eilish"),
        arrayOf("艾德·希兰", "Ed Sheeran"),
        arrayOf("布鲁诺·马尔斯", "Bruno Mars"),
        arrayOf("德雷克", "Drake"),
        arrayOf("肯德里克·拉马尔", "Kendrick Lamar"),
        arrayOf("弗兰克·奥申", "Frank Ocean"),
        arrayOf("诺拉·琼斯", "Norah Jones"),
        arrayOf("戴安娜·克瑞儿", "Diana Krall"),
        arrayOf("恩雅", "Enya"),
        arrayOf("雅尼", "Yanni"),
        arrayOf("理查德·克莱德曼", "Richard Clayderman"),
        arrayOf("班得瑞", "Bandari"),
    )

    /** 补充高频中英双向歌手名及 Apple Music 双语合写展示名（每组最后一项通常为英文规范名）。 */
    private val EXTRA_GROUPS: Array<Array<String>> = arrayOf(
        // 补充 Apple Music 常见双语合写名与变体
        arrayOf("邓紫棋", "G.E.M. 邓紫棋", "GEM", "G.E.M."),
        arrayOf("热狗", "MC HotDog 热狗", "MC HotDog"),
        arrayOf("飞儿乐团", "F.I.R. 飞儿乐团", "F.I.R."),
        arrayOf("张惠妹", "A-Mei 张惠妹", "aMEI", "A-Mei"),
        arrayOf("周延", "GAI周延", "GAI"),
        arrayOf("宇多田光", "宇多田ヒカル", "Utada", "Utada Hikaru", "Hikaru Utada"),
        arrayOf("米津玄师", "米津玄師", "Kenshi Yonezu"),
        arrayOf("藤井风", "藤井 風", "藤井風", "Fujii Kaze"),
        arrayOf("中岛美雪", "中島みゆき", "Miyuki Nakajima"),
        arrayOf("许嵩", "Xu Song", "Vae"),
        arrayOf("周深", "Charlie Zhou", "Zhou Shen"),
        arrayOf("吴青峰", "Qing Feng Wu", "Wu Qing-Feng"),
        arrayOf("泰勒·斯威夫特", "泰勒丝", "霉霉", "Taylor Swift"),
        arrayOf("比莉·艾利什", "比利·艾利什", "碧梨", "Billie Eilish"),
        arrayOf("艾德·希兰", "黄老板", "Ed Sheeran"),
        arrayOf("布鲁诺·马尔斯", "布鲁诺·马斯", "火星哥", "Bruno Mars"),
        arrayOf("阿黛尔", "爱黛儿", "Adele"),
        arrayOf("碧昂丝", "碧昂斯", "Beyoncé"),
        arrayOf("酷玩乐队", "酷玩", "Coldplay"),
        arrayOf("披头士", "披头士乐队", "甲壳虫乐队", "The Beatles"),
        arrayOf("迈克尔·杰克逊", "米高积逊", "Michael Jackson"),
        arrayOf("爱我的", "G-IDLE", "GIDLE", "(G)I-DLE"),
        // 欧美高频歌手中英对照
        arrayOf("威肯", "盆栽哥", "The Weeknd"),
        arrayOf("贾斯汀·比伯", "贾斯丁·比伯", "Justin Bieber"),
        arrayOf("爱莉安娜·格兰德", "阿丽亚娜·格兰德", "Ariana Grande"),
        arrayOf("蕾哈娜", "Rihanna"),
        arrayOf("拉娜·德雷", "打雷姐", "Lana Del Rey"),
        arrayOf("杜阿·利帕", "Dua Lipa"),
        arrayOf("查理·普斯", "断眉", "Charlie Puth"),
        arrayOf("肖恩·蒙德兹", "Shawn Mendes"),
        arrayOf("赛琳娜·戈麦斯", "Selena Gomez"),
        arrayOf("凯蒂·佩里", "水果姐", "Katy Perry"),
        arrayOf("嘎嘎小姐", "女神卡卡", "Lady Gaga"),
        arrayOf("萨布丽娜·卡彭特", "Sabrina Carpenter"),
        arrayOf("奥利维亚·罗德里戈", "Olivia Rodrigo"),
        arrayOf("哈里·斯泰尔斯", "Harry Styles"),
        arrayOf("萨姆·史密斯", "Sam Smith"),
        arrayOf("特洛耶·希文", "戳爷", "Troye Sivan"),
        arrayOf("艾米纳姆", "埃米纳姆", "姆爷", "Eminem"),
        arrayOf("坎耶·韦斯特", "肯伊·威斯特", "侃爷", "Kanye West"),
        arrayOf("特拉维斯·斯科特", "Travis Scott"),
        arrayOf("波兹·马龙", "Post Malone"),
        arrayOf("艾薇儿", "艾薇儿·拉维尼", "Avril Lavigne"),
        arrayOf("玛丽亚·凯莉", "Mariah Carey"),
        arrayOf("席琳·迪翁", "席琳·狄翁", "Céline Dion", "Celine Dion"),
        arrayOf("惠特尼·休斯顿", "惠妮·休斯顿", "Whitney Houston"),
        arrayOf("烟鬼组合", "烟鬼", "The Chainsmokers"),
        arrayOf("梦龙乐队", "想象龙", "Imagine Dragons"),
        arrayOf("魔力红", "Maroon 5"),
        arrayOf("共和时代", "OneRepublic"),
        arrayOf("林肯公园", "Linkin Park"),
        arrayOf("绿日乐队", "Green Day"),
        arrayOf("绿洲乐队", "Oasis"),
        arrayOf("西城男孩", "Westlife"),
        arrayOf("后街男孩", "Backstreet Boys"),
        arrayOf("单向乐队", "One Direction"),
        arrayOf("艾伦·沃克", "Alan Walker"),
        arrayOf("艾维奇", "Avicii"),
        arrayOf("蠢朋克", "Daft Punk"),
        arrayOf("麦莉·赛勒斯", "Miley Cyrus"),
        arrayOf("卡米拉·卡贝洛", "Camila Cabello"),
        arrayOf("希雅", "Sia"),
        arrayOf("粉红佳人", "P!nk"),
        arrayOf("豆荚猫", "Doja Cat"),
        arrayOf("布兰妮", "布兰妮·斯皮尔斯", "Britney Spears"),
        arrayOf("克里斯蒂娜·阿奎莱拉", "Christina Aguilera"),
        arrayOf("贾斯汀·汀布莱克", "Justin Timberlake"),
        arrayOf("夏奇拉", "Shakira"),
        arrayOf("埃尔顿·约翰", "Elton John"),
        arrayOf("约翰·梅尔", "John Mayer"),
        arrayOf("杰森·玛耶兹", "Jason Mraz"),
        arrayOf("猫王", "埃尔维斯·普雷斯利", "Elvis Presley"),
        arrayOf("弗兰克·辛纳屈", "Frank Sinatra"),
        arrayOf("金属乐队", "Metallica"),
        arrayOf("枪炮与玫瑰", "枪花", "Guns N' Roses"),
        arrayOf("邦乔维", "Bon Jovi"),
        arrayOf("红辣椒乐队", "Red Hot Chili Peppers"),
        arrayOf("北极猴", "Arctic Monkeys"),
        arrayOf("缪斯乐队", "Muse"),
        arrayOf("模糊乐队", "Blur"),
        arrayOf("事后烟", "Cigarettes After Sex"),
        arrayOf("林冰", "Laufey"),
        // 华语/港台高频歌手中英对照
        arrayOf("告五人", "Accusefive"),
        arrayOf("草东没有派对", "No Party For Cao Dong"),
        arrayOf("落日飞车", "Sunset Rollercoaster"),
        arrayOf("橘子海", "Orange Ocean"),
        arrayOf("回春丹", "Young Dan"),
        arrayOf("单依纯", "Shan Yichun"),
        arrayOf("黄霄云", "黄霄雲", "Ghost Huang"),
        arrayOf("周兴哲", "Eric Chou"),
        arrayOf("张韶涵", "Angela Chang"),
        arrayOf("王心凌", "Cyndi Wang"),
        arrayOf("杨丞琳", "Rainie Yang"),
        arrayOf("萧亚轩", "Elva Hsiao"),
        arrayOf("范玮琪", "Christine Fan"),
        arrayOf("郭静", "Claire Kuo"),
        arrayOf("郭采洁", "Amber Kuo"),
        arrayOf("戴佩妮", "Penny Tai"),
        arrayOf("黄丽玲", "A-Lin"),
        arrayOf("胡彦斌", "Tiger Hu"),
        arrayOf("张碧晨", "Diamond Zhang"),
        arrayOf("王嘉尔", "Jackson Wang"),
        arrayOf("刘宪华", "Henry Lau"),
        arrayOf("周传雄", "Steve Chou"),
        arrayOf("刀郎", "Dao Lang"),
        arrayOf("凤凰传奇", "Phoenix Legend"),
        arrayOf("张震岳", "Chang Chen-Yue", "A-Yue"),
        arrayOf("郭顶", "Guo Ding"),
        arrayOf("梁咏琪", "Gigi Leung"),
        arrayOf("古巨基", "Leo Ku"),
        arrayOf("陈小春", "Jordan Chan"),
        arrayOf("郑伊健", "Ekin Cheng"),
        arrayOf("林峰", "林峯", "Raymond Lam"),
        arrayOf("张天赋", "MC 张天赋", "MC Cheung Tinfu"),
        arrayOf("姜涛", "Keung To"),
        arrayOf("冯允谦", "Jay Fung"),
        arrayOf("洪嘉豪", "Kaho Hung"),
        arrayOf("汤令山", "Gareth.T"),
        arrayOf("陈蕾", "Panther Chan"),
        arrayOf("泳儿", "Vincy Chan"),
        arrayOf("薛凯琪", "Fiona Sit"),
        arrayOf("陈柏宇", "Jason Chan"),
        arrayOf("周国贤", "Endy Chow"),
        arrayOf("张卫健", "Dicky Cheung"),
        arrayOf("梁汉文", "Edmond Leung"),
        arrayOf("梁朝伟", "Tony Leung"),
        arrayOf("林志炫", "Terry Lin"),
        arrayOf("胡夏", "Hu Xia"),
        arrayOf("周笔畅", "Bibi Zhou"),
        arrayOf("陈楚生", "Chen Chusheng"),
        arrayOf("张远", "Bird Zhang"),
        arrayOf("刘宇宁", "摩登兄弟刘宇宁", "Liu Yuning"),
        arrayOf("颜人中", "Ele Yan"),
        arrayOf("郑润泽", "Zheng Runze"),
        arrayOf("队长", "YoungCaptain"),
        arrayOf("魏如萱", "waa wei"),
        arrayOf("安溥", "张悬", "焦安溥", "Anpu", "Deserts Chang"),
        arrayOf("范晓萱", "Mavis Fan"),
        arrayOf("许茹芸", "Valen Hsu"),
        arrayOf("彭佳慧", "Julia Peng"),
        arrayOf("苏慧伦", "Tarcy Su"),
        arrayOf("江蕙", "Jody Chiang"),
        arrayOf("萧煌奇", "Ricky Hsiao"),
        arrayOf("齐豫", "Chyi Yu"),
        arrayOf("陈淑桦", "Sarah Chen"),
        arrayOf("许美静", "Mavis Hee"),
        arrayOf("阿杜", "A-Do"),
        arrayOf("阿桑", "A-Sun"),
        arrayOf("崔健", "Cui Jian"),
        arrayOf("窦唯", "Dou Wei"),
        arrayOf("唐朝乐队", "Tang Dynasty"),
        arrayOf("羽泉", "Yu Quan"),
        arrayOf("大张伟", "Wowkie Zhang"),
        arrayOf("徐小凤", "Paula Tsui"),
        arrayOf("罗文", "Roman Tam"),
        arrayOf("卢冠廷", "Lowell Lo"),
        arrayOf("茄子蛋", "EggPlantEgg"),
        arrayOf("鱼丁糸", "oaeen"),
        arrayOf("八三夭", "831"),
        arrayOf("时代少年团", "Teens in Times"),
        arrayOf("肖战", "Xiao Zhan"),
        arrayOf("王一博", "Wang Yibo"),
        arrayOf("宋雨琦", "YUQI"),
        arrayOf("高尔宣", "OSN"),
        arrayOf("瘦子", "瘦子E.SO", "E.SO"),
        arrayOf("顽童MJ116", "MJ116"),
        arrayOf("夏日入侵企画", "SummerVapour"),
        arrayOf("傻子与白痴", "Fool and Idiot"),
        arrayOf("椅子乐团", "The Chairs"),
        arrayOf("重塑雕像的权利", "Re-TROS"),
        arrayOf("旅行团乐队", "The LifeJourney"),
        arrayOf("盘尼西林", "Penicillin"),
        arrayOf("南拳妈妈", "Nan Quan Mama"),
        arrayOf("严爵", "Yen-j"),
        arrayOf("庄心妍", "Ada Zhuang"),
        arrayOf("江海迦", "AGA"),
        arrayOf("李幸倪", "Gin Lee"),
        arrayOf("杜德伟", "Alex To"),
        arrayOf("林志颖", "Jimmy Lin"),
    )

    /** 常见日语新字体汉字 → 简体字折叠表（补齐 ICU Traditional-Simplified 不转换的日语新字体差异）。 */
    private val SHINJITAI_TO_SIMPLIFIED: Map<Char, Char> = mapOf(
        '亜' to '亚', '円' to '圆', '応' to '应', '仮' to '假', '価' to '价',
        '絵' to '绘', '覚' to '觉', '観' to '观', '顔' to '颜', '帰' to '归',
        '気' to '气', '響' to '响', '暁' to '晓', '剣' to '剑', '険' to '险',
        '顕' to '显', '験' to '验', '厳' to '严', '広' to '广', '黒' to '黑',
        '歳' to '岁', '桜' to '樱', '雑' to '杂', '産' to '产', '児' to '儿',
        '実' to '实', '釈' to '释', '従' to '从', '渋' to '涩', '獣' to '兽',
        '縦' to '纵', '処' to '处', '焼' to '烧', '浄' to '净', '譲' to '让',
        '図' to '图', '酔' to '醉', '穂' to '穗', '瀬' to '濑', '斉' to '齐',
        '斎' to '斋', '専' to '专', '戦' to '战', '繊' to '纤', '総' to '总',
        '騒' to '骚', '増' to '增', '臓' to '脏', '蔵' to '藏', '続' to '续',
        '対' to '对', '帯' to '带', '滝' to '泷', '択' to '择', '沢' to '泽',
        '単' to '单', '団' to '团', '弾' to '弹', '遅' to '迟', '聴' to '听',
        '鉄' to '铁', '転' to '转', '伝' to '传', '闘' to '斗', '徳' to '德',
        '読' to '读', '縄' to '绳', '悩' to '恼', '脳' to '脑', '拝' to '拜',
        '廃' to '废', '売' to '卖', '発' to '发', '抜' to '拔', '晩' to '晚',
        '浜' to '滨', '仏' to '佛', '並' to '并', '変' to '变', '辺' to '边',
        '舗' to '铺', '歩' to '步', '豊' to '丰', '毎' to '每', '満' to '满',
        '黙' to '默', '薬' to '药', '訳' to '译', '揺' to '摇', '様' to '样',
        '頼' to '赖', '覧' to '览', '竜' to '龙', '両' to '两', '緑' to '绿',
        '涙' to '泪', '霊' to '灵', '齢' to '龄', '歴' to '历', '労' to '劳',
        '恵' to '惠', '楽' to '乐', '関' to '关', '栄' to '荣', '蛍' to '萤',
        '亀' to '龟'
    )

    private val PAREN_BILINGUAL_REGEX = Regex("""^(.+?)\s*[(（]([^()（）]+)[)）]\s*$""")

    private class BuiltIndex(
        val canonicalTable: Map<String, String>,
        val groupsByKey: Map<String, List<String>>
    )

    private val index: BuiltIndex by lazy { buildIndex() }

    private fun buildIndex(): BuiltIndex {
        val canonical = HashMap<String, String>()
        val parent = HashMap<String, String>()
        val displayByKey = LinkedHashMap<String, String>()

        fun find(x: String): String {
            var cur = x
            while (parent[cur] != cur) {
                cur = parent[cur] ?: break
            }
            var node = x
            while (parent[node] != null && parent[node] != cur) {
                val next = parent[node]!!
                parent[node] = cur
                node = next
            }
            return cur
        }

        fun registerKey(raw: String): String {
            val key = normalizeKey(raw)
            if (key.isEmpty()) return ""
            if (key !in parent) parent[key] = key
            if (key !in displayByKey) displayByKey[key] = raw.trim()
            return key
        }

        fun union(a: String, b: String) {
            if (a.isEmpty() || b.isEmpty()) return
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        fun ingestGroup(group: Array<String>) {
            if (group.size < 2) return
            val keys = group.mapNotNull { raw -> registerKey(raw).takeIf { it.isNotEmpty() } }
            if (keys.isEmpty()) return
            val root = keys.first()
            for (i in 1 until keys.size) {
                union(root, keys[i])
            }
            val canonicalName = group.last().trim()
            val canonicalKey = normalizeKey(canonicalName)
            if (canonicalKey.isNotEmpty()) {
                for (i in 0 until group.size - 1) {
                    val localKey = normalizeKey(group[i])
                    if (localKey.isNotEmpty() && localKey != canonicalKey) {
                        canonical[localKey] = canonicalName
                    }
                }
            }
        }

        for (p in PAIRS) ingestGroup(p)
        for (g in EXTRA_GROUPS) ingestGroup(g)

        val buckets = LinkedHashMap<String, ArrayList<String>>()
        for ((key, raw) in displayByKey) {
            val root = find(key)
            buckets.getOrPut(root) { ArrayList() }.add(raw)
        }

        val groupsByKey = HashMap<String, List<String>>(displayByKey.size * 2)
        for ((key, _) in displayByKey) {
            val members = buckets[find(key)] ?: continue
            groupsByKey[key] = members
        }

        return BuiltIndex(
            canonicalTable = canonical,
            groupsByKey = groupsByKey
        )
    }

    @JvmStatic
    fun hasHanCharacters(text: String): Boolean =
        text.any { it in '\u3400'..'\u9FFF' || it in '\uF900'..'\uFAFF' }

    @JvmStatic
    fun hasJapaneseKana(text: String): Boolean =
        text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' || it in '\uFF66'..'\uFF9F' }

    @JvmStatic
    fun hasLatinLetters(text: String): Boolean =
        text.any { it in 'A'..'Z' || it in 'a'..'z' || it in '\u00C0'..'\u024F' }

    /**
     * 精准匹配用归一化键：
     * - 繁转简 + 常见日语新字体折叠；
     * - 仅剥离拉丁组合重音符（保留日语浊音/半浊音仮名不变）；
     * - 忽略大小写、空白、中点及常见装饰标点。
     */
    @JvmStatic
    fun normalizeKey(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val simplified = ChineseText.toSimplified(trimmed)
        val nfkc = Normalizer.normalize(simplified, Normalizer.Form.NFKC)
        val nfkd = Normalizer.normalize(nfkc, Normalizer.Form.NFKD)
        val strippedAccents = StringBuilder(nfkd.length)
        for (ch in nfkd) {
            // 只移除拉丁组合重音符（U+0300..U+036F），保留日语浊点/半浊点（U+3099..U+309A）
            if (ch in '\u0300'..'\u036F') continue
            strippedAccents.append(ch)
        }
        val composed = Normalizer.normalize(strippedAccents.toString(), Normalizer.Form.NFC)
        val sb = StringBuilder(composed.length)
        for (rawCh in composed) {
            val mapped = SHINJITAI_TO_SIMPLIFIED[rawCh] ?: rawCh
            val ch = mapped.lowercaseChar()
            when (ch) {
                'ø' -> {
                    sb.append('o')
                    continue
                }
                'ł' -> {
                    sb.append('l')
                    continue
                }
                'đ' -> {
                    sb.append('d')
                    continue
                }
                'æ' -> {
                    sb.append("ae")
                    continue
                }
                'œ' -> {
                    sb.append("oe")
                    continue
                }
                'ß' -> {
                    sb.append("ss")
                    continue
                }
            }
            if (Character.isWhitespace(ch) ||
                ch == '·' || ch == '・' || ch == '•' || ch == '‧' || ch == '･' ||
                ch == '.' || ch == ',' || ch == '-' || ch == '_' ||
                ch == '~' || ch == '～' || ch == '—' || ch == '–' ||
                ch == '\'' || ch == '’' || ch == '‘' || ch == '`' ||
                ch == '。' || ch == '、' || ch == '，' ||
                ch == '!' || ch == '！'
            ) {
                continue
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /** 命中返回目录规范名（主要用于中文名 → 英文规范名检索），未命中返回空串。 */
    @JvmStatic
    fun lookup(term: String): String {
        val key = normalizeKey(term)
        if (key.isEmpty()) return ""
        return index.canonicalTable[key] ?: ""
    }

    /** 双向查询该歌手的等价别名组（中文名 ↔ 英文名）。 */
    @JvmStatic
    fun aliasesOf(term: String): List<String> {
        val key = normalizeKey(term)
        if (key.isEmpty()) return emptyList()
        return index.groupsByKey[key] ?: emptyList()
    }

    /** 返回与当前输入不同规范键的跨语种候选搜索词（优先返回另一语种主名称）。 */
    @JvmStatic
    fun alternateSearchTerms(term: String): List<String> {
        val queryKey = normalizeKey(term)
        if (queryKey.isEmpty()) return emptyList()
        val aliases = index.groupsByKey[queryKey] ?: return emptyList()
        val queryHasHan = hasHanCharacters(term)
        val queryHasLatin = hasLatinLetters(term) && !queryHasHan

        val preferred = ArrayList<String>()
        val fallback = ArrayList<String>()
        val seenKeys = HashSet<String>().apply { add(queryKey) }

        val canonical = index.canonicalTable[queryKey]
        if (!canonical.isNullOrBlank()) {
            val cKey = normalizeKey(canonical)
            if (seenKeys.add(cKey)) preferred.add(canonical)
        }

        for (alias in aliases) {
            val aKey = normalizeKey(alias)
            if (aKey.isEmpty() || !seenKeys.add(aKey)) continue
            val isOppositeScript = when {
                queryHasHan -> hasLatinLetters(alias) && !hasHanCharacters(alias)
                queryHasLatin -> hasHanCharacters(alias)
                else -> true
            }
            if (isOppositeScript) preferred.add(alias) else fallback.add(alias)
        }
        return preferred + fallback
    }

    /** 计算输入查询词允许精准命中的所有归一化键集合。 */
    @JvmStatic
    fun exactTargetKeys(query: String): Set<String> {
        val queryKey = normalizeKey(query)
        if (queryKey.isEmpty()) return emptySet()
        val keys = LinkedHashSet<String>()
        keys.add(queryKey)
        for (alias in aliasesOf(query)) {
            val aKey = normalizeKey(alias)
            if (aKey.isNotEmpty()) keys.add(aKey)
        }
        return keys
    }

    /** 提取艺人显示名的可精准比对变体（含跨语种括号合写，如「周杰伦 (Jay Chou)」）。 */
    @JvmStatic
    fun extractCandidateNameVariants(name: String): List<String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return emptyList()
        val variants = ArrayList<String>(3)
        variants.add(trimmed)
        val match = PAREN_BILINGUAL_REGEX.matchEntire(trimmed)
        if (match != null) {
            val outer = match.groupValues[1].trim()
            val inner = match.groupValues[2].trim()
            val outerCjk = hasHanCharacters(outer) || hasJapaneseKana(outer)
            val innerCjk = hasHanCharacters(inner) || hasJapaneseKana(inner)
            val outerLatin = hasLatinLetters(outer) && !outerCjk
            val innerLatin = hasLatinLetters(inner) && !innerCjk
            if ((outerCjk && innerLatin) || (outerLatin && innerCjk)) {
                if (outer.isNotEmpty()) variants.add(outer)
                if (inner.isNotEmpty()) variants.add(inner)
            }
        }
        return variants
    }

    /** 判断候选艺人名是否精准匹配 [targetKeys]（支持双向中英等价组与日语原名）。 */
    @JvmStatic
    fun matchesTargetKeys(candidateName: String, targetKeys: Set<String>): Boolean {
        if (targetKeys.isEmpty() || candidateName.isBlank()) return false
        for (variant in extractCandidateNameVariants(candidateName)) {
            val key = normalizeKey(variant)
            if (key.isEmpty()) continue
            if (key in targetKeys) return true
            val group = index.groupsByKey[key]
            if (group != null) {
                for (alias in group) {
                    val aKey = normalizeKey(alias)
                    if (aKey.isNotEmpty() && aKey in targetKeys) return true
                }
            }
        }
        return false
    }

    @JvmStatic
    fun matchesArtistName(candidateName: String, query: String): Boolean {
        val targetKeys = exactTargetKeys(query)
        return targetKeys.isNotEmpty() && matchesTargetKeys(candidateName, targetKeys)
    }

    fun size(): Int = index.canonicalTable.size
}
