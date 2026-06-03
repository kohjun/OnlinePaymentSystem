/*
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
$(document).ready(function() {

    $(".click-title").mouseenter( function(    e){
        e.preventDefault();
        this.style.cursor="pointer";
    });
    $(".click-title").mousedown( function(event){
        event.preventDefault();
    });

    // Ugly code while this script is shared among several pages
    try{
        refreshHitsPerSecond(true);
    } catch(e){}
    try{
        refreshResponseTimeOverTime(true);
    } catch(e){}
    try{
        refreshResponseTimePercentiles();
    } catch(e){}
});


var responseTimePercentilesInfos = {
        data: {"result": {"minY": 38.0, "minX": 0.0, "maxY": 4407.0, "series": [{"data": [[0.0, 38.0], [0.1, 55.0], [0.2, 83.0], [0.3, 96.0], [0.4, 117.0], [0.5, 128.0], [0.6, 138.0], [0.7, 143.0], [0.8, 152.0], [0.9, 158.0], [1.0, 163.0], [1.1, 166.0], [1.2, 171.0], [1.3, 174.0], [1.4, 177.0], [1.5, 180.0], [1.6, 184.0], [1.7, 186.0], [1.8, 189.0], [1.9, 190.0], [2.0, 193.0], [2.1, 195.0], [2.2, 198.0], [2.3, 199.0], [2.4, 202.0], [2.5, 202.0], [2.6, 205.0], [2.7, 207.0], [2.8, 208.0], [2.9, 210.0], [3.0, 212.0], [3.1, 214.0], [3.2, 215.0], [3.3, 217.0], [3.4, 218.0], [3.5, 220.0], [3.6, 223.0], [3.7, 224.0], [3.8, 226.0], [3.9, 227.0], [4.0, 228.0], [4.1, 230.0], [4.2, 231.0], [4.3, 233.0], [4.4, 234.0], [4.5, 235.0], [4.6, 236.0], [4.7, 238.0], [4.8, 239.0], [4.9, 240.0], [5.0, 241.0], [5.1, 244.0], [5.2, 245.0], [5.3, 247.0], [5.4, 249.0], [5.5, 250.0], [5.6, 253.0], [5.7, 255.0], [5.8, 256.0], [5.9, 257.0], [6.0, 258.0], [6.1, 259.0], [6.2, 260.0], [6.3, 262.0], [6.4, 263.0], [6.5, 263.0], [6.6, 266.0], [6.7, 268.0], [6.8, 268.0], [6.9, 269.0], [7.0, 270.0], [7.1, 272.0], [7.2, 273.0], [7.3, 274.0], [7.4, 276.0], [7.5, 278.0], [7.6, 279.0], [7.7, 280.0], [7.8, 281.0], [7.9, 282.0], [8.0, 283.0], [8.1, 284.0], [8.2, 284.0], [8.3, 286.0], [8.4, 287.0], [8.5, 287.0], [8.6, 288.0], [8.7, 289.0], [8.8, 290.0], [8.9, 291.0], [9.0, 292.0], [9.1, 293.0], [9.2, 294.0], [9.3, 296.0], [9.4, 297.0], [9.5, 298.0], [9.6, 300.0], [9.7, 300.0], [9.8, 302.0], [9.9, 304.0], [10.0, 305.0], [10.1, 306.0], [10.2, 307.0], [10.3, 307.0], [10.4, 309.0], [10.5, 310.0], [10.6, 311.0], [10.7, 312.0], [10.8, 313.0], [10.9, 314.0], [11.0, 315.0], [11.1, 316.0], [11.2, 317.0], [11.3, 318.0], [11.4, 319.0], [11.5, 320.0], [11.6, 321.0], [11.7, 322.0], [11.8, 324.0], [11.9, 325.0], [12.0, 326.0], [12.1, 326.0], [12.2, 328.0], [12.3, 329.0], [12.4, 331.0], [12.5, 332.0], [12.6, 332.0], [12.7, 334.0], [12.8, 335.0], [12.9, 337.0], [13.0, 338.0], [13.1, 339.0], [13.2, 340.0], [13.3, 341.0], [13.4, 342.0], [13.5, 344.0], [13.6, 344.0], [13.7, 346.0], [13.8, 346.0], [13.9, 348.0], [14.0, 348.0], [14.1, 349.0], [14.2, 350.0], [14.3, 351.0], [14.4, 352.0], [14.5, 353.0], [14.6, 354.0], [14.7, 354.0], [14.8, 355.0], [14.9, 357.0], [15.0, 358.0], [15.1, 359.0], [15.2, 359.0], [15.3, 360.0], [15.4, 361.0], [15.5, 362.0], [15.6, 363.0], [15.7, 363.0], [15.8, 364.0], [15.9, 365.0], [16.0, 366.0], [16.1, 367.0], [16.2, 367.0], [16.3, 368.0], [16.4, 369.0], [16.5, 370.0], [16.6, 371.0], [16.7, 372.0], [16.8, 372.0], [16.9, 373.0], [17.0, 374.0], [17.1, 375.0], [17.2, 376.0], [17.3, 376.0], [17.4, 377.0], [17.5, 379.0], [17.6, 379.0], [17.7, 381.0], [17.8, 383.0], [17.9, 385.0], [18.0, 386.0], [18.1, 387.0], [18.2, 388.0], [18.3, 389.0], [18.4, 390.0], [18.5, 391.0], [18.6, 391.0], [18.7, 392.0], [18.8, 394.0], [18.9, 395.0], [19.0, 396.0], [19.1, 397.0], [19.2, 397.0], [19.3, 399.0], [19.4, 400.0], [19.5, 401.0], [19.6, 402.0], [19.7, 402.0], [19.8, 403.0], [19.9, 404.0], [20.0, 405.0], [20.1, 407.0], [20.2, 408.0], [20.3, 408.0], [20.4, 410.0], [20.5, 411.0], [20.6, 412.0], [20.7, 413.0], [20.8, 413.0], [20.9, 414.0], [21.0, 415.0], [21.1, 416.0], [21.2, 418.0], [21.3, 419.0], [21.4, 420.0], [21.5, 420.0], [21.6, 422.0], [21.7, 422.0], [21.8, 424.0], [21.9, 425.0], [22.0, 426.0], [22.1, 427.0], [22.2, 428.0], [22.3, 429.0], [22.4, 430.0], [22.5, 431.0], [22.6, 432.0], [22.7, 433.0], [22.8, 434.0], [22.9, 435.0], [23.0, 436.0], [23.1, 437.0], [23.2, 437.0], [23.3, 438.0], [23.4, 439.0], [23.5, 440.0], [23.6, 441.0], [23.7, 442.0], [23.8, 442.0], [23.9, 443.0], [24.0, 443.0], [24.1, 444.0], [24.2, 445.0], [24.3, 445.0], [24.4, 446.0], [24.5, 447.0], [24.6, 448.0], [24.7, 449.0], [24.8, 450.0], [24.9, 452.0], [25.0, 452.0], [25.1, 453.0], [25.2, 454.0], [25.3, 455.0], [25.4, 456.0], [25.5, 457.0], [25.6, 458.0], [25.7, 459.0], [25.8, 460.0], [25.9, 462.0], [26.0, 464.0], [26.1, 465.0], [26.2, 466.0], [26.3, 467.0], [26.4, 468.0], [26.5, 470.0], [26.6, 471.0], [26.7, 473.0], [26.8, 473.0], [26.9, 474.0], [27.0, 476.0], [27.1, 477.0], [27.2, 478.0], [27.3, 479.0], [27.4, 480.0], [27.5, 482.0], [27.6, 482.0], [27.7, 483.0], [27.8, 485.0], [27.9, 485.0], [28.0, 486.0], [28.1, 487.0], [28.2, 488.0], [28.3, 489.0], [28.4, 490.0], [28.5, 491.0], [28.6, 492.0], [28.7, 493.0], [28.8, 494.0], [28.9, 496.0], [29.0, 498.0], [29.1, 499.0], [29.2, 500.0], [29.3, 502.0], [29.4, 503.0], [29.5, 504.0], [29.6, 505.0], [29.7, 507.0], [29.8, 507.0], [29.9, 508.0], [30.0, 509.0], [30.1, 511.0], [30.2, 512.0], [30.3, 513.0], [30.4, 514.0], [30.5, 514.0], [30.6, 515.0], [30.7, 516.0], [30.8, 518.0], [30.9, 518.0], [31.0, 519.0], [31.1, 521.0], [31.2, 523.0], [31.3, 523.0], [31.4, 524.0], [31.5, 525.0], [31.6, 525.0], [31.7, 527.0], [31.8, 528.0], [31.9, 529.0], [32.0, 530.0], [32.1, 531.0], [32.2, 532.0], [32.3, 533.0], [32.4, 534.0], [32.5, 535.0], [32.6, 537.0], [32.7, 539.0], [32.8, 540.0], [32.9, 542.0], [33.0, 543.0], [33.1, 543.0], [33.2, 546.0], [33.3, 548.0], [33.4, 549.0], [33.5, 551.0], [33.6, 553.0], [33.7, 555.0], [33.8, 556.0], [33.9, 557.0], [34.0, 558.0], [34.1, 559.0], [34.2, 560.0], [34.3, 561.0], [34.4, 563.0], [34.5, 564.0], [34.6, 566.0], [34.7, 567.0], [34.8, 568.0], [34.9, 569.0], [35.0, 570.0], [35.1, 572.0], [35.2, 573.0], [35.3, 574.0], [35.4, 576.0], [35.5, 577.0], [35.6, 579.0], [35.7, 581.0], [35.8, 583.0], [35.9, 585.0], [36.0, 586.0], [36.1, 587.0], [36.2, 589.0], [36.3, 591.0], [36.4, 592.0], [36.5, 593.0], [36.6, 596.0], [36.7, 597.0], [36.8, 598.0], [36.9, 599.0], [37.0, 602.0], [37.1, 604.0], [37.2, 606.0], [37.3, 606.0], [37.4, 608.0], [37.5, 610.0], [37.6, 614.0], [37.7, 615.0], [37.8, 616.0], [37.9, 618.0], [38.0, 620.0], [38.1, 621.0], [38.2, 623.0], [38.3, 625.0], [38.4, 628.0], [38.5, 631.0], [38.6, 634.0], [38.7, 636.0], [38.8, 637.0], [38.9, 638.0], [39.0, 640.0], [39.1, 642.0], [39.2, 645.0], [39.3, 647.0], [39.4, 648.0], [39.5, 652.0], [39.6, 654.0], [39.7, 656.0], [39.8, 658.0], [39.9, 659.0], [40.0, 661.0], [40.1, 663.0], [40.2, 665.0], [40.3, 667.0], [40.4, 669.0], [40.5, 672.0], [40.6, 675.0], [40.7, 676.0], [40.8, 680.0], [40.9, 682.0], [41.0, 683.0], [41.1, 685.0], [41.2, 686.0], [41.3, 688.0], [41.4, 690.0], [41.5, 693.0], [41.6, 696.0], [41.7, 700.0], [41.8, 701.0], [41.9, 703.0], [42.0, 706.0], [42.1, 707.0], [42.2, 711.0], [42.3, 714.0], [42.4, 718.0], [42.5, 719.0], [42.6, 721.0], [42.7, 725.0], [42.8, 726.0], [42.9, 728.0], [43.0, 731.0], [43.1, 734.0], [43.2, 735.0], [43.3, 736.0], [43.4, 740.0], [43.5, 740.0], [43.6, 743.0], [43.7, 745.0], [43.8, 748.0], [43.9, 749.0], [44.0, 751.0], [44.1, 754.0], [44.2, 758.0], [44.3, 762.0], [44.4, 764.0], [44.5, 765.0], [44.6, 768.0], [44.7, 771.0], [44.8, 774.0], [44.9, 775.0], [45.0, 779.0], [45.1, 782.0], [45.2, 785.0], [45.3, 788.0], [45.4, 792.0], [45.5, 794.0], [45.6, 799.0], [45.7, 803.0], [45.8, 807.0], [45.9, 809.0], [46.0, 812.0], [46.1, 816.0], [46.2, 819.0], [46.3, 823.0], [46.4, 824.0], [46.5, 827.0], [46.6, 829.0], [46.7, 831.0], [46.8, 834.0], [46.9, 836.0], [47.0, 840.0], [47.1, 845.0], [47.2, 848.0], [47.3, 852.0], [47.4, 855.0], [47.5, 857.0], [47.6, 860.0], [47.7, 863.0], [47.8, 865.0], [47.9, 868.0], [48.0, 871.0], [48.1, 873.0], [48.2, 877.0], [48.3, 880.0], [48.4, 883.0], [48.5, 888.0], [48.6, 891.0], [48.7, 894.0], [48.8, 898.0], [48.9, 905.0], [49.0, 908.0], [49.1, 913.0], [49.2, 915.0], [49.3, 917.0], [49.4, 923.0], [49.5, 930.0], [49.6, 935.0], [49.7, 937.0], [49.8, 939.0], [49.9, 945.0], [50.0, 950.0], [50.1, 954.0], [50.2, 957.0], [50.3, 959.0], [50.4, 962.0], [50.5, 965.0], [50.6, 968.0], [50.7, 970.0], [50.8, 977.0], [50.9, 983.0], [51.0, 988.0], [51.1, 992.0], [51.2, 996.0], [51.3, 1002.0], [51.4, 1008.0], [51.5, 1015.0], [51.6, 1019.0], [51.7, 1026.0], [51.8, 1035.0], [51.9, 1038.0], [52.0, 1047.0], [52.1, 1056.0], [52.2, 1059.0], [52.3, 1069.0], [52.4, 1074.0], [52.5, 1078.0], [52.6, 1083.0], [52.7, 1085.0], [52.8, 1088.0], [52.9, 1092.0], [53.0, 1097.0], [53.1, 1100.0], [53.2, 1105.0], [53.3, 1107.0], [53.4, 1110.0], [53.5, 1112.0], [53.6, 1115.0], [53.7, 1120.0], [53.8, 1123.0], [53.9, 1127.0], [54.0, 1129.0], [54.1, 1131.0], [54.2, 1134.0], [54.3, 1135.0], [54.4, 1138.0], [54.5, 1143.0], [54.6, 1146.0], [54.7, 1149.0], [54.8, 1150.0], [54.9, 1153.0], [55.0, 1156.0], [55.1, 1157.0], [55.2, 1159.0], [55.3, 1161.0], [55.4, 1162.0], [55.5, 1164.0], [55.6, 1166.0], [55.7, 1167.0], [55.8, 1167.0], [55.9, 1169.0], [56.0, 1170.0], [56.1, 1171.0], [56.2, 1171.0], [56.3, 1172.0], [56.4, 1174.0], [56.5, 1175.0], [56.6, 1176.0], [56.7, 1177.0], [56.8, 1178.0], [56.9, 1179.0], [57.0, 1180.0], [57.1, 1182.0], [57.2, 1183.0], [57.3, 1184.0], [57.4, 1185.0], [57.5, 1186.0], [57.6, 1187.0], [57.7, 1188.0], [57.8, 1189.0], [57.9, 1190.0], [58.0, 1190.0], [58.1, 1192.0], [58.2, 1193.0], [58.3, 1194.0], [58.4, 1195.0], [58.5, 1197.0], [58.6, 1197.0], [58.7, 1199.0], [58.8, 1200.0], [58.9, 1201.0], [59.0, 1202.0], [59.1, 1203.0], [59.2, 1204.0], [59.3, 1205.0], [59.4, 1206.0], [59.5, 1207.0], [59.6, 1208.0], [59.7, 1209.0], [59.8, 1209.0], [59.9, 1210.0], [60.0, 1211.0], [60.1, 1212.0], [60.2, 1212.0], [60.3, 1213.0], [60.4, 1214.0], [60.5, 1215.0], [60.6, 1215.0], [60.7, 1216.0], [60.8, 1217.0], [60.9, 1217.0], [61.0, 1218.0], [61.1, 1219.0], [61.2, 1220.0], [61.3, 1220.0], [61.4, 1222.0], [61.5, 1222.0], [61.6, 1223.0], [61.7, 1224.0], [61.8, 1224.0], [61.9, 1225.0], [62.0, 1225.0], [62.1, 1226.0], [62.2, 1227.0], [62.3, 1227.0], [62.4, 1228.0], [62.5, 1229.0], [62.6, 1229.0], [62.7, 1230.0], [62.8, 1231.0], [62.9, 1232.0], [63.0, 1232.0], [63.1, 1233.0], [63.2, 1233.0], [63.3, 1234.0], [63.4, 1235.0], [63.5, 1236.0], [63.6, 1237.0], [63.7, 1237.0], [63.8, 1238.0], [63.9, 1239.0], [64.0, 1240.0], [64.1, 1241.0], [64.2, 1242.0], [64.3, 1242.0], [64.4, 1243.0], [64.5, 1244.0], [64.6, 1245.0], [64.7, 1246.0], [64.8, 1247.0], [64.9, 1247.0], [65.0, 1248.0], [65.1, 1248.0], [65.2, 1249.0], [65.3, 1250.0], [65.4, 1250.0], [65.5, 1251.0], [65.6, 1252.0], [65.7, 1252.0], [65.8, 1253.0], [65.9, 1254.0], [66.0, 1254.0], [66.1, 1255.0], [66.2, 1256.0], [66.3, 1257.0], [66.4, 1257.0], [66.5, 1258.0], [66.6, 1258.0], [66.7, 1259.0], [66.8, 1260.0], [66.9, 1260.0], [67.0, 1261.0], [67.1, 1262.0], [67.2, 1262.0], [67.3, 1263.0], [67.4, 1264.0], [67.5, 1264.0], [67.6, 1265.0], [67.7, 1266.0], [67.8, 1266.0], [67.9, 1267.0], [68.0, 1268.0], [68.1, 1268.0], [68.2, 1269.0], [68.3, 1270.0], [68.4, 1271.0], [68.5, 1272.0], [68.6, 1272.0], [68.7, 1273.0], [68.8, 1273.0], [68.9, 1274.0], [69.0, 1274.0], [69.1, 1275.0], [69.2, 1276.0], [69.3, 1276.0], [69.4, 1277.0], [69.5, 1277.0], [69.6, 1278.0], [69.7, 1278.0], [69.8, 1280.0], [69.9, 1280.0], [70.0, 1280.0], [70.1, 1281.0], [70.2, 1282.0], [70.3, 1283.0], [70.4, 1283.0], [70.5, 1285.0], [70.6, 1285.0], [70.7, 1286.0], [70.8, 1287.0], [70.9, 1287.0], [71.0, 1288.0], [71.1, 1288.0], [71.2, 1289.0], [71.3, 1289.0], [71.4, 1290.0], [71.5, 1291.0], [71.6, 1291.0], [71.7, 1291.0], [71.8, 1292.0], [71.9, 1293.0], [72.0, 1293.0], [72.1, 1294.0], [72.2, 1295.0], [72.3, 1296.0], [72.4, 1296.0], [72.5, 1297.0], [72.6, 1298.0], [72.7, 1298.0], [72.8, 1299.0], [72.9, 1300.0], [73.0, 1300.0], [73.1, 1301.0], [73.2, 1302.0], [73.3, 1302.0], [73.4, 1304.0], [73.5, 1304.0], [73.6, 1304.0], [73.7, 1305.0], [73.8, 1306.0], [73.9, 1307.0], [74.0, 1307.0], [74.1, 1308.0], [74.2, 1309.0], [74.3, 1309.0], [74.4, 1310.0], [74.5, 1311.0], [74.6, 1311.0], [74.7, 1311.0], [74.8, 1312.0], [74.9, 1313.0], [75.0, 1313.0], [75.1, 1314.0], [75.2, 1315.0], [75.3, 1316.0], [75.4, 1316.0], [75.5, 1317.0], [75.6, 1318.0], [75.7, 1318.0], [75.8, 1319.0], [75.9, 1319.0], [76.0, 1320.0], [76.1, 1320.0], [76.2, 1321.0], [76.3, 1322.0], [76.4, 1323.0], [76.5, 1323.0], [76.6, 1324.0], [76.7, 1324.0], [76.8, 1325.0], [76.9, 1326.0], [77.0, 1327.0], [77.1, 1327.0], [77.2, 1328.0], [77.3, 1329.0], [77.4, 1330.0], [77.5, 1330.0], [77.6, 1330.0], [77.7, 1331.0], [77.8, 1331.0], [77.9, 1332.0], [78.0, 1333.0], [78.1, 1333.0], [78.2, 1334.0], [78.3, 1335.0], [78.4, 1336.0], [78.5, 1337.0], [78.6, 1338.0], [78.7, 1339.0], [78.8, 1340.0], [78.9, 1341.0], [79.0, 1342.0], [79.1, 1343.0], [79.2, 1344.0], [79.3, 1344.0], [79.4, 1345.0], [79.5, 1346.0], [79.6, 1347.0], [79.7, 1347.0], [79.8, 1349.0], [79.9, 1350.0], [80.0, 1350.0], [80.1, 1351.0], [80.2, 1352.0], [80.3, 1352.0], [80.4, 1353.0], [80.5, 1354.0], [80.6, 1354.0], [80.7, 1355.0], [80.8, 1355.0], [80.9, 1356.0], [81.0, 1357.0], [81.1, 1358.0], [81.2, 1359.0], [81.3, 1360.0], [81.4, 1360.0], [81.5, 1363.0], [81.6, 1364.0], [81.7, 1364.0], [81.8, 1365.0], [81.9, 1366.0], [82.0, 1366.0], [82.1, 1367.0], [82.2, 1368.0], [82.3, 1369.0], [82.4, 1369.0], [82.5, 1370.0], [82.6, 1371.0], [82.7, 1372.0], [82.8, 1373.0], [82.9, 1373.0], [83.0, 1374.0], [83.1, 1375.0], [83.2, 1375.0], [83.3, 1376.0], [83.4, 1377.0], [83.5, 1378.0], [83.6, 1379.0], [83.7, 1380.0], [83.8, 1380.0], [83.9, 1381.0], [84.0, 1382.0], [84.1, 1382.0], [84.2, 1383.0], [84.3, 1384.0], [84.4, 1384.0], [84.5, 1386.0], [84.6, 1386.0], [84.7, 1387.0], [84.8, 1388.0], [84.9, 1389.0], [85.0, 1390.0], [85.1, 1391.0], [85.2, 1392.0], [85.3, 1393.0], [85.4, 1393.0], [85.5, 1394.0], [85.6, 1395.0], [85.7, 1396.0], [85.8, 1397.0], [85.9, 1398.0], [86.0, 1399.0], [86.1, 1400.0], [86.2, 1400.0], [86.3, 1401.0], [86.4, 1402.0], [86.5, 1402.0], [86.6, 1404.0], [86.7, 1404.0], [86.8, 1405.0], [86.9, 1406.0], [87.0, 1407.0], [87.1, 1408.0], [87.2, 1409.0], [87.3, 1410.0], [87.4, 1411.0], [87.5, 1412.0], [87.6, 1412.0], [87.7, 1413.0], [87.8, 1414.0], [87.9, 1415.0], [88.0, 1415.0], [88.1, 1416.0], [88.2, 1416.0], [88.3, 1417.0], [88.4, 1417.0], [88.5, 1418.0], [88.6, 1419.0], [88.7, 1419.0], [88.8, 1420.0], [88.9, 1421.0], [89.0, 1423.0], [89.1, 1425.0], [89.2, 1426.0], [89.3, 1427.0], [89.4, 1429.0], [89.5, 1430.0], [89.6, 1432.0], [89.7, 1434.0], [89.8, 1434.0], [89.9, 1436.0], [90.0, 1436.0], [90.1, 1438.0], [90.2, 1440.0], [90.3, 1441.0], [90.4, 1442.0], [90.5, 1443.0], [90.6, 1444.0], [90.7, 1446.0], [90.8, 1447.0], [90.9, 1448.0], [91.0, 1449.0], [91.1, 1450.0], [91.2, 1452.0], [91.3, 1453.0], [91.4, 1455.0], [91.5, 1456.0], [91.6, 1458.0], [91.7, 1458.0], [91.8, 1460.0], [91.9, 1462.0], [92.0, 1464.0], [92.1, 1465.0], [92.2, 1465.0], [92.3, 1467.0], [92.4, 1469.0], [92.5, 1470.0], [92.6, 1472.0], [92.7, 1475.0], [92.8, 1477.0], [92.9, 1478.0], [93.0, 1481.0], [93.1, 1482.0], [93.2, 1484.0], [93.3, 1487.0], [93.4, 1488.0], [93.5, 1490.0], [93.6, 1491.0], [93.7, 1493.0], [93.8, 1496.0], [93.9, 1498.0], [94.0, 1499.0], [94.1, 1501.0], [94.2, 1503.0], [94.3, 1504.0], [94.4, 1506.0], [94.5, 1510.0], [94.6, 1510.0], [94.7, 1512.0], [94.8, 1515.0], [94.9, 1516.0], [95.0, 1518.0], [95.1, 1523.0], [95.2, 1525.0], [95.3, 1527.0], [95.4, 1530.0], [95.5, 1534.0], [95.6, 1536.0], [95.7, 1539.0], [95.8, 1542.0], [95.9, 1547.0], [96.0, 1550.0], [96.1, 1555.0], [96.2, 1560.0], [96.3, 1564.0], [96.4, 1569.0], [96.5, 1577.0], [96.6, 1580.0], [96.7, 1584.0], [96.8, 1591.0], [96.9, 1598.0], [97.0, 1606.0], [97.1, 1613.0], [97.2, 1619.0], [97.3, 1625.0], [97.4, 1633.0], [97.5, 1646.0], [97.6, 1657.0], [97.7, 1678.0], [97.8, 1686.0], [97.9, 1706.0], [98.0, 1734.0], [98.1, 1757.0], [98.2, 1769.0], [98.3, 1792.0], [98.4, 1800.0], [98.5, 1825.0], [98.6, 1866.0], [98.7, 1907.0], [98.8, 1948.0], [98.9, 2031.0], [99.0, 2105.0], [99.1, 2233.0], [99.2, 2279.0], [99.3, 2505.0], [99.4, 2643.0], [99.5, 3036.0], [99.6, 3205.0], [99.7, 3347.0], [99.8, 3676.0], [99.9, 3883.0]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "maxX": 100.0, "title": "Response Time Percentiles"}},
        getOptions: function() {
            return {
                series: {
                    points: { show: false }
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendResponseTimePercentiles'
                },
                xaxis: {
                    tickDecimals: 1,
                    axisLabel: "Percentiles",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Percentile value in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : %x.2 percentile was %y ms"
                },
                selection: { mode: "xy" },
            };
        },
        createGraph: function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesResponseTimePercentiles"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotResponseTimesPercentiles"), dataset, options);
            // setup overview
            $.plot($("#overviewResponseTimesPercentiles"), dataset, prepareOverviewOptions(options));
        }
};

/**
 * @param elementId Id of element where we display message
 */
function setEmptyGraph(elementId) {
    $(function() {
        $(elementId).text("No graph series with filter="+seriesFilter);
    });
}

// Response times percentiles
function refreshResponseTimePercentiles() {
    var infos = responseTimePercentilesInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyResponseTimePercentiles");
        return;
    }
    if (isGraph($("#flotResponseTimesPercentiles"))){
        infos.createGraph();
    } else {
        var choiceContainer = $("#choicesResponseTimePercentiles");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotResponseTimesPercentiles", "#overviewResponseTimesPercentiles");
        $('#bodyResponseTimePercentiles .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
}

var responseTimeDistributionInfos = {
        data: {"result": {"minY": 1.0, "minX": 0.0, "maxY": 918.0, "series": [{"data": [[0.0, 20.0], [600.0, 311.0], [700.0, 256.0], [800.0, 209.0], [900.0, 159.0], [1000.0, 117.0], [1100.0, 373.0], [1200.0, 918.0], [1300.0, 857.0], [1400.0, 521.0], [1500.0, 190.0], [1600.0, 60.0], [100.0, 132.0], [1700.0, 33.0], [1800.0, 19.0], [1900.0, 11.0], [2000.0, 9.0], [2100.0, 6.0], [2200.0, 9.0], [2300.0, 4.0], [2400.0, 1.0], [2500.0, 5.0], [2600.0, 4.0], [2800.0, 1.0], [2700.0, 2.0], [2900.0, 1.0], [3000.0, 4.0], [3100.0, 2.0], [3300.0, 5.0], [3200.0, 5.0], [200.0, 473.0], [3400.0, 1.0], [3500.0, 1.0], [3600.0, 3.0], [3700.0, 4.0], [3800.0, 3.0], [4000.0, 2.0], [4100.0, 1.0], [4300.0, 1.0], [4400.0, 1.0], [300.0, 636.0], [400.0, 638.0], [500.0, 504.0]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 100, "maxX": 4400.0, "title": "Response Time Distribution"}},
        getOptions: function() {
            var granularity = this.data.result.granularity;
            return {
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendResponseTimeDistribution'
                },
                xaxis:{
                    axisLabel: "Response times in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of responses",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                bars : {
                    show: true,
                    barWidth: this.data.result.granularity
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: function(label, xval, yval, flotItem){
                        return yval + " responses for " + label + " were between " + xval + " and " + (xval + granularity) + " ms";
                    }
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotResponseTimeDistribution"), prepareData(data.result.series, $("#choicesResponseTimeDistribution")), options);
        }

};

// Response time distribution
function refreshResponseTimeDistribution() {
    var infos = responseTimeDistributionInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyResponseTimeDistribution");
        return;
    }
    if (isGraph($("#flotResponseTimeDistribution"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesResponseTimeDistribution");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        $('#footerResponseTimeDistribution .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};


var syntheticResponseTimeDistributionInfos = {
        data: {"result": {"minY": 3.0, "minX": 2.0, "ticks": [[0, "Requests having \nresponse time <= 500ms"], [1, "Requests having \nresponse time > 500ms and <= 1,500ms"], [2, "Requests having \nresponse time > 1,500ms"], [3, "Requests in error"]], "maxY": 6509.0, "series": [{"data": [], "color": "#9ACD32", "isOverall": false, "label": "Requests having \nresponse time <= 500ms", "isController": false}, {"data": [], "color": "yellow", "isOverall": false, "label": "Requests having \nresponse time > 500ms and <= 1,500ms", "isController": false}, {"data": [[2.0, 3.0]], "color": "orange", "isOverall": false, "label": "Requests having \nresponse time > 1,500ms", "isController": false}, {"data": [[3.0, 6509.0]], "color": "#FF6347", "isOverall": false, "label": "Requests in error", "isController": false}], "supportsControllersDiscrimination": false, "maxX": 3.0, "title": "Synthetic Response Times Distribution"}},
        getOptions: function() {
            return {
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendSyntheticResponseTimeDistribution'
                },
                xaxis:{
                    axisLabel: "Response times ranges",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                    tickLength:0,
                    min:-0.5,
                    max:3.5
                },
                yaxis: {
                    axisLabel: "Number of responses",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                bars : {
                    show: true,
                    align: "center",
                    barWidth: 0.25,
                    fill:.75
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: function(label, xval, yval, flotItem){
                        return yval + " " + label;
                    }
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var options = this.getOptions();
            prepareOptions(options, data);
            options.xaxis.ticks = data.result.ticks;
            $.plot($("#flotSyntheticResponseTimeDistribution"), prepareData(data.result.series, $("#choicesSyntheticResponseTimeDistribution")), options);
        }

};

// Response time distribution
function refreshSyntheticResponseTimeDistribution() {
    var infos = syntheticResponseTimeDistributionInfos;
    prepareSeries(infos.data, true);
    if (isGraph($("#flotSyntheticResponseTimeDistribution"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesSyntheticResponseTimeDistribution");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        $('#footerSyntheticResponseTimeDistribution .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var activeThreadsOverTimeInfos = {
        data: {"result": {"minY": 99.0144144144144, "minX": 1.76294856E12, "maxY": 100.0, "series": [{"data": [[1.76294862E12, 99.0144144144144], [1.76294856E12, 100.0]], "isOverall": false, "label": "Gradual Load Test", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294862E12, "title": "Active Threads Over Time"}},
        getOptions: function() {
            return {
                series: {
                    stack: true,
                    lines: {
                        show: true,
                        fill: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of active threads",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: {
                    noColumns: 6,
                    show: true,
                    container: '#legendActiveThreadsOverTime'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                selection: {
                    mode: 'xy'
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : At %x there were %y active threads"
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesActiveThreadsOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotActiveThreadsOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewActiveThreadsOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Active Threads Over Time
function refreshActiveThreadsOverTime(fixTimestamps) {
    var infos = activeThreadsOverTimeInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotActiveThreadsOverTime"))) {
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesActiveThreadsOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotActiveThreadsOverTime", "#overviewActiveThreadsOverTime");
        $('#footerActiveThreadsOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var timeVsThreadsInfos = {
        data: {"result": {"minY": 289.0, "minX": 1.0, "maxY": 1394.0, "series": [{"data": [[2.0, 785.0], [3.0, 929.0], [4.0, 1047.0], [5.0, 1109.0], [6.0, 937.0], [7.0, 774.0], [8.0, 711.0], [9.0, 785.0], [10.0, 957.0], [11.0, 731.0], [12.0, 1074.0], [13.0, 1110.0], [14.0, 1161.0], [15.0, 1076.0], [16.0, 817.0], [17.0, 869.0], [18.0, 1261.0], [20.0, 742.0], [21.0, 875.0], [22.0, 1026.0], [23.0, 943.0], [25.0, 747.5], [26.0, 1209.0], [27.0, 947.0], [28.0, 879.0], [29.0, 863.0], [30.0, 727.0], [31.0, 843.0], [33.0, 794.0], [32.0, 1107.0], [35.0, 525.0], [34.0, 1166.0], [36.0, 787.0], [38.0, 629.0], [40.0, 1140.0], [43.0, 635.0], [42.0, 1025.5], [45.0, 879.0], [46.0, 1083.0], [49.0, 1089.0], [48.0, 699.5], [51.0, 773.5], [52.0, 1044.0], [57.0, 1389.0], [56.0, 1006.5], [59.0, 718.5], [61.0, 810.5], [63.0, 1309.0], [62.0, 386.0], [66.0, 779.0], [64.0, 289.0], [71.0, 1166.0], [70.0, 422.0], [69.0, 939.0], [68.0, 436.5], [75.0, 595.0], [73.0, 439.0], [72.0, 1257.0], [79.0, 1210.0], [78.0, 479.0], [77.0, 670.5], [83.0, 354.0], [82.0, 434.0], [81.0, 1286.0], [86.0, 503.0], [85.0, 1394.0], [84.0, 507.0], [90.0, 1265.0], [89.0, 910.0], [88.0, 370.0], [95.0, 490.0], [93.0, 537.0], [92.0, 1134.5], [99.0, 901.0], [97.0, 1027.5], [100.0, 919.1451738655835], [1.0, 846.0]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}, {"data": [[99.24401105651104, 918.2223587223573]], "isOverall": false, "label": "Complete Reservation (Saga Test)-Aggregated", "isController": false}], "supportsControllersDiscrimination": true, "maxX": 100.0, "title": "Time VS Threads"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    axisLabel: "Number of active threads",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Average response times in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: { noColumns: 2,show: true, container: '#legendTimeVsThreads' },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s: At %x.2 active threads, Average response time was %y.2 ms"
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesTimeVsThreads"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotTimesVsThreads"), dataset, options);
            // setup overview
            $.plot($("#overviewTimesVsThreads"), dataset, prepareOverviewOptions(options));
        }
};

// Time vs threads
function refreshTimeVsThreads(){
    var infos = timeVsThreadsInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyTimeVsThreads");
        return;
    }
    if(isGraph($("#flotTimesVsThreads"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesTimeVsThreads");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotTimesVsThreads", "#overviewTimesVsThreads");
        $('#footerTimeVsThreads .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var bytesThroughputOverTimeInfos = {
        data : {"result": {"minY": 5993.433333333333, "minX": 1.76294856E12, "maxY": 43623.0, "series": [{"data": [[1.76294862E12, 19720.333333333332], [1.76294856E12, 5993.433333333333]], "isOverall": false, "label": "Bytes received per second", "isController": false}, {"data": [[1.76294862E12, 43623.0], [1.76294856E12, 13248.466666666667]], "isOverall": false, "label": "Bytes sent per second", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294862E12, "title": "Bytes Throughput Over Time"}},
        getOptions : function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity) ,
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Bytes / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendBytesThroughputOverTime'
                },
                selection: {
                    mode: "xy"
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s at %x was %y"
                }
            };
        },
        createGraph : function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesBytesThroughputOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotBytesThroughputOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewBytesThroughputOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Bytes throughput Over Time
function refreshBytesThroughputOverTime(fixTimestamps) {
    var infos = bytesThroughputOverTimeInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotBytesThroughputOverTime"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesBytesThroughputOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotBytesThroughputOverTime", "#overviewBytesThroughputOverTime");
        $('#footerBytesThroughputOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
}

var responseTimesOverTimeInfos = {
        data: {"result": {"minY": 910.328728728728, "minX": 1.76294856E12, "maxY": 944.2135794330904, "series": [{"data": [[1.76294862E12, 910.328728728728], [1.76294856E12, 944.2135794330904]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294862E12, "title": "Response Time Over Time"}},
        getOptions: function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Average response time in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendResponseTimesOverTime'
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : at %x Average response time was %y ms"
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesResponseTimesOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotResponseTimesOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewResponseTimesOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Response Times Over Time
function refreshResponseTimeOverTime(fixTimestamps) {
    var infos = responseTimesOverTimeInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyResponseTimeOverTime");
        return;
    }
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotResponseTimesOverTime"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesResponseTimesOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotResponseTimesOverTime", "#overviewResponseTimesOverTime");
        $('#footerResponseTimesOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var latenciesOverTimeInfos = {
        data: {"result": {"minY": 893.4264264264268, "minX": 1.76294856E12, "maxY": 927.9380355965732, "series": [{"data": [[1.76294862E12, 893.4264264264268], [1.76294856E12, 927.9380355965732]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294862E12, "title": "Latencies Over Time"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Average response latencies in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendLatenciesOverTime'
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : at %x Average latency was %y ms"
                }
            };
        },
        createGraph: function () {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesLatenciesOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotLatenciesOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewLatenciesOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Latencies Over Time
function refreshLatenciesOverTime(fixTimestamps) {
    var infos = latenciesOverTimeInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyLatenciesOverTime");
        return;
    }
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotLatenciesOverTime"))) {
        infos.createGraph();
    }else {
        var choiceContainer = $("#choicesLatenciesOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotLatenciesOverTime", "#overviewLatenciesOverTime");
        $('#footerLatenciesOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var connectTimeOverTimeInfos = {
        data: {"result": {"minY": 1.453253253253255, "minX": 1.76294856E12, "maxY": 1.8259723137771913, "series": [{"data": [[1.76294862E12, 1.453253253253255], [1.76294856E12, 1.8259723137771913]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294862E12, "title": "Connect Time Over Time"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getConnectTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Average Connect Time in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendConnectTimeOverTime'
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : at %x Average connect time was %y ms"
                }
            };
        },
        createGraph: function () {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesConnectTimeOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotConnectTimeOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewConnectTimeOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Connect Time Over Time
function refreshConnectTimeOverTime(fixTimestamps) {
    var infos = connectTimeOverTimeInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyConnectTimeOverTime");
        return;
    }
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotConnectTimeOverTime"))) {
        infos.createGraph();
    }else {
        var choiceContainer = $("#choicesConnectTimeOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotConnectTimeOverTime", "#overviewConnectTimeOverTime");
        $('#footerConnectTimeOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var responseTimePercentilesOverTimeInfos = {
        data: {"result": {"minY": 4148.0, "minX": 1.76294856E12, "maxY": 4407.0, "series": [{"data": [[1.76294856E12, 4407.0]], "isOverall": false, "label": "Max", "isController": false}, {"data": [[1.76294856E12, 4407.0]], "isOverall": false, "label": "90th percentile", "isController": false}, {"data": [[1.76294856E12, 4407.0]], "isOverall": false, "label": "99th percentile", "isController": false}, {"data": [[1.76294856E12, 4407.0]], "isOverall": false, "label": "95th percentile", "isController": false}, {"data": [[1.76294856E12, 4148.0]], "isOverall": false, "label": "Min", "isController": false}, {"data": [[1.76294856E12, 4360.0]], "isOverall": false, "label": "Median", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294856E12, "title": "Response Time Percentiles Over Time (successful requests only)"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true,
                        fill: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Response Time in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendResponseTimePercentilesOverTime'
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : at %x Response time was %y ms"
                }
            };
        },
        createGraph: function () {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesResponseTimePercentilesOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotResponseTimePercentilesOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewResponseTimePercentilesOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Response Time Percentiles Over Time
function refreshResponseTimePercentilesOverTime(fixTimestamps) {
    var infos = responseTimePercentilesOverTimeInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotResponseTimePercentilesOverTime"))) {
        infos.createGraph();
    }else {
        var choiceContainer = $("#choicesResponseTimePercentilesOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotResponseTimePercentilesOverTime", "#overviewResponseTimePercentilesOverTime");
        $('#footerResponseTimePercentilesOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};


var responseTimeVsRequestInfos = {
    data: {"result": {"minY": 435.0, "minX": 22.0, "maxY": 4360.0, "series": [{"data": [[81.0, 4360.0]], "isOverall": false, "label": "Successes", "isController": false}, {"data": [[38.0, 1729.0], [72.0, 1062.5], [81.0, 435.0], [83.0, 1289.0], [82.0, 1335.0], [95.0, 1190.0], [96.0, 713.5], [97.0, 1232.0], [98.0, 1076.5], [99.0, 1315.0], [100.0, 816.0], [101.0, 1050.0], [103.0, 934.0], [106.0, 1235.0], [105.0, 915.0], [110.0, 743.0], [108.0, 1145.5], [112.0, 1234.5], [113.0, 1128.0], [115.0, 1251.0], [114.0, 937.0], [117.0, 853.0], [119.0, 678.5], [116.0, 596.5], [123.0, 675.0], [122.0, 875.0], [120.0, 1037.0], [125.0, 1262.0], [127.0, 893.0], [135.0, 506.0], [132.0, 811.5], [134.0, 890.0], [128.0, 986.5], [131.0, 786.0], [142.0, 660.5], [139.0, 496.0], [145.0, 857.0], [22.0, 2299.0], [29.0, 891.0]], "isOverall": false, "label": "Failures", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 1000, "maxX": 145.0, "title": "Response Time Vs Request"}},
    getOptions: function() {
        return {
            series: {
                lines: {
                    show: false
                },
                points: {
                    show: true
                }
            },
            xaxis: {
                axisLabel: "Global number of requests per second",
                axisLabelUseCanvas: true,
                axisLabelFontSizePixels: 12,
                axisLabelFontFamily: 'Verdana, Arial',
                axisLabelPadding: 20,
            },
            yaxis: {
                axisLabel: "Median Response Time in ms",
                axisLabelUseCanvas: true,
                axisLabelFontSizePixels: 12,
                axisLabelFontFamily: 'Verdana, Arial',
                axisLabelPadding: 20,
            },
            legend: {
                noColumns: 2,
                show: true,
                container: '#legendResponseTimeVsRequest'
            },
            selection: {
                mode: 'xy'
            },
            grid: {
                hoverable: true // IMPORTANT! this is needed for tooltip to work
            },
            tooltip: true,
            tooltipOpts: {
                content: "%s : Median response time at %x req/s was %y ms"
            },
            colors: ["#9ACD32", "#FF6347"]
        };
    },
    createGraph: function () {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesResponseTimeVsRequest"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotResponseTimeVsRequest"), dataset, options);
        // setup overview
        $.plot($("#overviewResponseTimeVsRequest"), dataset, prepareOverviewOptions(options));

    }
};

// Response Time vs Request
function refreshResponseTimeVsRequest() {
    var infos = responseTimeVsRequestInfos;
    prepareSeries(infos.data);
    if (isGraph($("#flotResponseTimeVsRequest"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesResponseTimeVsRequest");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotResponseTimeVsRequest", "#overviewResponseTimeVsRequest");
        $('#footerResponseRimeVsRequest .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};


var latenciesVsRequestInfos = {
    data: {"result": {"minY": 434.5, "minX": 22.0, "maxY": 4359.0, "series": [{"data": [[81.0, 4359.0]], "isOverall": false, "label": "Successes", "isController": false}, {"data": [[38.0, 1722.5], [72.0, 1040.0], [81.0, 434.5], [83.0, 1221.0], [82.0, 1279.0], [95.0, 1153.0], [96.0, 713.0], [97.0, 1188.0], [98.0, 1075.5], [99.0, 1275.0], [100.0, 816.0], [101.0, 1015.5], [103.0, 934.0], [106.0, 1208.0], [105.0, 915.0], [110.0, 742.5], [108.0, 1108.0], [112.0, 1210.0], [113.0, 1115.0], [115.0, 1215.0], [114.0, 937.0], [117.0, 852.5], [119.0, 678.5], [116.0, 596.0], [123.0, 675.0], [122.0, 874.5], [120.0, 1034.0], [125.0, 1241.0], [127.0, 893.0], [135.0, 506.0], [132.0, 811.5], [134.0, 889.5], [128.0, 985.5], [131.0, 786.0], [142.0, 660.0], [139.0, 495.0], [145.0, 857.0], [22.0, 2261.5], [29.0, 891.0]], "isOverall": false, "label": "Failures", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 1000, "maxX": 145.0, "title": "Latencies Vs Request"}},
    getOptions: function() {
        return{
            series: {
                lines: {
                    show: false
                },
                points: {
                    show: true
                }
            },
            xaxis: {
                axisLabel: "Global number of requests per second",
                axisLabelUseCanvas: true,
                axisLabelFontSizePixels: 12,
                axisLabelFontFamily: 'Verdana, Arial',
                axisLabelPadding: 20,
            },
            yaxis: {
                axisLabel: "Median Latency in ms",
                axisLabelUseCanvas: true,
                axisLabelFontSizePixels: 12,
                axisLabelFontFamily: 'Verdana, Arial',
                axisLabelPadding: 20,
            },
            legend: { noColumns: 2,show: true, container: '#legendLatencyVsRequest' },
            selection: {
                mode: 'xy'
            },
            grid: {
                hoverable: true // IMPORTANT! this is needed for tooltip to work
            },
            tooltip: true,
            tooltipOpts: {
                content: "%s : Median Latency time at %x req/s was %y ms"
            },
            colors: ["#9ACD32", "#FF6347"]
        };
    },
    createGraph: function () {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesLatencyVsRequest"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotLatenciesVsRequest"), dataset, options);
        // setup overview
        $.plot($("#overviewLatenciesVsRequest"), dataset, prepareOverviewOptions(options));
    }
};

// Latencies vs Request
function refreshLatenciesVsRequest() {
        var infos = latenciesVsRequestInfos;
        prepareSeries(infos.data);
        if(isGraph($("#flotLatenciesVsRequest"))){
            infos.createGraph();
        }else{
            var choiceContainer = $("#choicesLatencyVsRequest");
            createLegend(choiceContainer, infos);
            infos.createGraph();
            setGraphZoomable("#flotLatenciesVsRequest", "#overviewLatenciesVsRequest");
            $('#footerLatenciesVsRequest .legendColorBox > div').each(function(i){
                $(this).clone().prependTo(choiceContainer.find("li").eq(i));
            });
        }
};

var hitsPerSecondInfos = {
        data: {"result": {"minY": 26.933333333333334, "minX": 1.76294856E12, "maxY": 81.6, "series": [{"data": [[1.76294862E12, 81.6], [1.76294856E12, 26.933333333333334]], "isOverall": false, "label": "hitsPerSecond", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294862E12, "title": "Hits Per Second"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of hits / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: "#legendHitsPerSecond"
                },
                selection: {
                    mode : 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s at %x was %y.2 hits/sec"
                }
            };
        },
        createGraph: function createGraph() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesHitsPerSecond"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotHitsPerSecond"), dataset, options);
            // setup overview
            $.plot($("#overviewHitsPerSecond"), dataset, prepareOverviewOptions(options));
        }
};

// Hits per second
function refreshHitsPerSecond(fixTimestamps) {
    var infos = hitsPerSecondInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if (isGraph($("#flotHitsPerSecond"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesHitsPerSecond");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotHitsPerSecond", "#overviewHitsPerSecond");
        $('#footerHitsPerSecond .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
}

var codesPerSecondInfos = {
        data: {"result": {"minY": 0.05, "minX": 1.76294856E12, "maxY": 83.25, "series": [{"data": [[1.76294856E12, 0.05]], "isOverall": false, "label": "200", "isController": false}, {"data": [[1.76294862E12, 83.25], [1.76294856E12, 25.233333333333334]], "isOverall": false, "label": "400", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294862E12, "title": "Codes Per Second"}},
        getOptions: function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of responses / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: "#legendCodesPerSecond"
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "Number of Response Codes %s at %x was %y.2 responses / sec"
                }
            };
        },
    createGraph: function() {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesCodesPerSecond"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotCodesPerSecond"), dataset, options);
        // setup overview
        $.plot($("#overviewCodesPerSecond"), dataset, prepareOverviewOptions(options));
    }
};

// Codes per second
function refreshCodesPerSecond(fixTimestamps) {
    var infos = codesPerSecondInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotCodesPerSecond"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesCodesPerSecond");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotCodesPerSecond", "#overviewCodesPerSecond");
        $('#footerCodesPerSecond .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var transactionsPerSecondInfos = {
        data: {"result": {"minY": 0.05, "minX": 1.76294856E12, "maxY": 83.25, "series": [{"data": [[1.76294862E12, 83.25], [1.76294856E12, 25.233333333333334]], "isOverall": false, "label": "Complete Reservation (Saga Test)-failure", "isController": false}, {"data": [[1.76294856E12, 0.05]], "isOverall": false, "label": "Complete Reservation (Saga Test)-success", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294862E12, "title": "Transactions Per Second"}},
        getOptions: function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of transactions / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: "#legendTransactionsPerSecond"
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s at %x was %y transactions / sec"
                }
            };
        },
    createGraph: function () {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesTransactionsPerSecond"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotTransactionsPerSecond"), dataset, options);
        // setup overview
        $.plot($("#overviewTransactionsPerSecond"), dataset, prepareOverviewOptions(options));
    }
};

// Transactions per second
function refreshTransactionsPerSecond(fixTimestamps) {
    var infos = transactionsPerSecondInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyTransactionsPerSecond");
        return;
    }
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotTransactionsPerSecond"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesTransactionsPerSecond");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotTransactionsPerSecond", "#overviewTransactionsPerSecond");
        $('#footerTransactionsPerSecond .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var totalTPSInfos = {
        data: {"result": {"minY": 0.05, "minX": 1.76294856E12, "maxY": 83.25, "series": [{"data": [[1.76294856E12, 0.05]], "isOverall": false, "label": "Transaction-success", "isController": false}, {"data": [[1.76294862E12, 83.25], [1.76294856E12, 25.233333333333334]], "isOverall": false, "label": "Transaction-failure", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294862E12, "title": "Total Transactions Per Second"}},
        getOptions: function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of transactions / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: "#legendTotalTPS"
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s at %x was %y transactions / sec"
                },
                colors: ["#9ACD32", "#FF6347"]
            };
        },
    createGraph: function () {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesTotalTPS"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotTotalTPS"), dataset, options);
        // setup overview
        $.plot($("#overviewTotalTPS"), dataset, prepareOverviewOptions(options));
    }
};

// Total Transactions per second
function refreshTotalTPS(fixTimestamps) {
    var infos = totalTPSInfos;
    // We want to ignore seriesFilter
    prepareSeries(infos.data, false, true);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotTotalTPS"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesTotalTPS");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotTotalTPS", "#overviewTotalTPS");
        $('#footerTotalTPS .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

// Collapse the graph matching the specified DOM element depending the collapsed
// status
function collapse(elem, collapsed){
    if(collapsed){
        $(elem).parent().find(".fa-chevron-up").removeClass("fa-chevron-up").addClass("fa-chevron-down");
    } else {
        $(elem).parent().find(".fa-chevron-down").removeClass("fa-chevron-down").addClass("fa-chevron-up");
        if (elem.id == "bodyBytesThroughputOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshBytesThroughputOverTime(true);
            }
            document.location.href="#bytesThroughputOverTime";
        } else if (elem.id == "bodyLatenciesOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshLatenciesOverTime(true);
            }
            document.location.href="#latenciesOverTime";
        } else if (elem.id == "bodyCustomGraph") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshCustomGraph(true);
            }
            document.location.href="#responseCustomGraph";
        } else if (elem.id == "bodyConnectTimeOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshConnectTimeOverTime(true);
            }
            document.location.href="#connectTimeOverTime";
        } else if (elem.id == "bodyResponseTimePercentilesOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshResponseTimePercentilesOverTime(true);
            }
            document.location.href="#responseTimePercentilesOverTime";
        } else if (elem.id == "bodyResponseTimeDistribution") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshResponseTimeDistribution();
            }
            document.location.href="#responseTimeDistribution" ;
        } else if (elem.id == "bodySyntheticResponseTimeDistribution") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshSyntheticResponseTimeDistribution();
            }
            document.location.href="#syntheticResponseTimeDistribution" ;
        } else if (elem.id == "bodyActiveThreadsOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshActiveThreadsOverTime(true);
            }
            document.location.href="#activeThreadsOverTime";
        } else if (elem.id == "bodyTimeVsThreads") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshTimeVsThreads();
            }
            document.location.href="#timeVsThreads" ;
        } else if (elem.id == "bodyCodesPerSecond") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshCodesPerSecond(true);
            }
            document.location.href="#codesPerSecond";
        } else if (elem.id == "bodyTransactionsPerSecond") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshTransactionsPerSecond(true);
            }
            document.location.href="#transactionsPerSecond";
        } else if (elem.id == "bodyTotalTPS") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshTotalTPS(true);
            }
            document.location.href="#totalTPS";
        } else if (elem.id == "bodyResponseTimeVsRequest") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshResponseTimeVsRequest();
            }
            document.location.href="#responseTimeVsRequest";
        } else if (elem.id == "bodyLatenciesVsRequest") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshLatenciesVsRequest();
            }
            document.location.href="#latencyVsRequest";
        }
    }
}

/*
 * Activates or deactivates all series of the specified graph (represented by id parameter)
 * depending on checked argument.
 */
function toggleAll(id, checked){
    var placeholder = document.getElementById(id);

    var cases = $(placeholder).find(':checkbox');
    cases.prop('checked', checked);
    $(cases).parent().children().children().toggleClass("legend-disabled", !checked);

    var choiceContainer;
    if ( id == "choicesBytesThroughputOverTime"){
        choiceContainer = $("#choicesBytesThroughputOverTime");
        refreshBytesThroughputOverTime(false);
    } else if(id == "choicesResponseTimesOverTime"){
        choiceContainer = $("#choicesResponseTimesOverTime");
        refreshResponseTimeOverTime(false);
    }else if(id == "choicesResponseCustomGraph"){
        choiceContainer = $("#choicesResponseCustomGraph");
        refreshCustomGraph(false);
    } else if ( id == "choicesLatenciesOverTime"){
        choiceContainer = $("#choicesLatenciesOverTime");
        refreshLatenciesOverTime(false);
    } else if ( id == "choicesConnectTimeOverTime"){
        choiceContainer = $("#choicesConnectTimeOverTime");
        refreshConnectTimeOverTime(false);
    } else if ( id == "choicesResponseTimePercentilesOverTime"){
        choiceContainer = $("#choicesResponseTimePercentilesOverTime");
        refreshResponseTimePercentilesOverTime(false);
    } else if ( id == "choicesResponseTimePercentiles"){
        choiceContainer = $("#choicesResponseTimePercentiles");
        refreshResponseTimePercentiles();
    } else if(id == "choicesActiveThreadsOverTime"){
        choiceContainer = $("#choicesActiveThreadsOverTime");
        refreshActiveThreadsOverTime(false);
    } else if ( id == "choicesTimeVsThreads"){
        choiceContainer = $("#choicesTimeVsThreads");
        refreshTimeVsThreads();
    } else if ( id == "choicesSyntheticResponseTimeDistribution"){
        choiceContainer = $("#choicesSyntheticResponseTimeDistribution");
        refreshSyntheticResponseTimeDistribution();
    } else if ( id == "choicesResponseTimeDistribution"){
        choiceContainer = $("#choicesResponseTimeDistribution");
        refreshResponseTimeDistribution();
    } else if ( id == "choicesHitsPerSecond"){
        choiceContainer = $("#choicesHitsPerSecond");
        refreshHitsPerSecond(false);
    } else if(id == "choicesCodesPerSecond"){
        choiceContainer = $("#choicesCodesPerSecond");
        refreshCodesPerSecond(false);
    } else if ( id == "choicesTransactionsPerSecond"){
        choiceContainer = $("#choicesTransactionsPerSecond");
        refreshTransactionsPerSecond(false);
    } else if ( id == "choicesTotalTPS"){
        choiceContainer = $("#choicesTotalTPS");
        refreshTotalTPS(false);
    } else if ( id == "choicesResponseTimeVsRequest"){
        choiceContainer = $("#choicesResponseTimeVsRequest");
        refreshResponseTimeVsRequest();
    } else if ( id == "choicesLatencyVsRequest"){
        choiceContainer = $("#choicesLatencyVsRequest");
        refreshLatenciesVsRequest();
    }
    var color = checked ? "black" : "#818181";
    if(choiceContainer != null) {
        choiceContainer.find("label").each(function(){
            this.style.color = color;
        });
    }
}

