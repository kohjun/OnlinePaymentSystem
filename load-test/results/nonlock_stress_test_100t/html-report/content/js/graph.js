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
        data: {"result": {"minY": 46.0, "minX": 0.0, "maxY": 10225.0, "series": [{"data": [[0.0, 46.0], [0.1, 66.0], [0.2, 191.0], [0.3, 205.0], [0.4, 222.0], [0.5, 233.0], [0.6, 255.0], [0.7, 261.0], [0.8, 276.0], [0.9, 286.0], [1.0, 293.0], [1.1, 303.0], [1.2, 306.0], [1.3, 310.0], [1.4, 323.0], [1.5, 327.0], [1.6, 339.0], [1.7, 343.0], [1.8, 348.0], [1.9, 354.0], [2.0, 356.0], [2.1, 360.0], [2.2, 363.0], [2.3, 366.0], [2.4, 369.0], [2.5, 370.0], [2.6, 374.0], [2.7, 379.0], [2.8, 382.0], [2.9, 386.0], [3.0, 388.0], [3.1, 390.0], [3.2, 392.0], [3.3, 393.0], [3.4, 396.0], [3.5, 397.0], [3.6, 399.0], [3.7, 400.0], [3.8, 402.0], [3.9, 404.0], [4.0, 405.0], [4.1, 407.0], [4.2, 408.0], [4.3, 410.0], [4.4, 411.0], [4.5, 413.0], [4.6, 415.0], [4.7, 417.0], [4.8, 419.0], [4.9, 421.0], [5.0, 423.0], [5.1, 424.0], [5.2, 426.0], [5.3, 427.0], [5.4, 428.0], [5.5, 429.0], [5.6, 431.0], [5.7, 433.0], [5.8, 435.0], [5.9, 437.0], [6.0, 437.0], [6.1, 440.0], [6.2, 441.0], [6.3, 443.0], [6.4, 444.0], [6.5, 448.0], [6.6, 449.0], [6.7, 451.0], [6.8, 453.0], [6.9, 454.0], [7.0, 456.0], [7.1, 459.0], [7.2, 461.0], [7.3, 463.0], [7.4, 465.0], [7.5, 468.0], [7.6, 470.0], [7.7, 474.0], [7.8, 478.0], [7.9, 481.0], [8.0, 483.0], [8.1, 484.0], [8.2, 485.0], [8.3, 488.0], [8.4, 490.0], [8.5, 493.0], [8.6, 495.0], [8.7, 497.0], [8.8, 499.0], [8.9, 502.0], [9.0, 503.0], [9.1, 506.0], [9.2, 507.0], [9.3, 508.0], [9.4, 509.0], [9.5, 511.0], [9.6, 512.0], [9.7, 514.0], [9.8, 515.0], [9.9, 518.0], [10.0, 518.0], [10.1, 519.0], [10.2, 520.0], [10.3, 521.0], [10.4, 521.0], [10.5, 522.0], [10.6, 524.0], [10.7, 525.0], [10.8, 526.0], [10.9, 527.0], [11.0, 528.0], [11.1, 530.0], [11.2, 532.0], [11.3, 533.0], [11.4, 535.0], [11.5, 537.0], [11.6, 538.0], [11.7, 539.0], [11.8, 540.0], [11.9, 541.0], [12.0, 542.0], [12.1, 543.0], [12.2, 544.0], [12.3, 545.0], [12.4, 546.0], [12.5, 547.0], [12.6, 548.0], [12.7, 549.0], [12.8, 549.0], [12.9, 550.0], [13.0, 551.0], [13.1, 551.0], [13.2, 551.0], [13.3, 552.0], [13.4, 553.0], [13.5, 554.0], [13.6, 555.0], [13.7, 556.0], [13.8, 557.0], [13.9, 557.0], [14.0, 558.0], [14.1, 558.0], [14.2, 558.0], [14.3, 559.0], [14.4, 560.0], [14.5, 561.0], [14.6, 562.0], [14.7, 563.0], [14.8, 563.0], [14.9, 564.0], [15.0, 565.0], [15.1, 565.0], [15.2, 566.0], [15.3, 568.0], [15.4, 569.0], [15.5, 570.0], [15.6, 570.0], [15.7, 571.0], [15.8, 571.0], [15.9, 572.0], [16.0, 572.0], [16.1, 574.0], [16.2, 575.0], [16.3, 575.0], [16.4, 577.0], [16.5, 578.0], [16.6, 578.0], [16.7, 579.0], [16.8, 580.0], [16.9, 581.0], [17.0, 582.0], [17.1, 583.0], [17.2, 585.0], [17.3, 586.0], [17.4, 587.0], [17.5, 588.0], [17.6, 589.0], [17.7, 590.0], [17.8, 590.0], [17.9, 591.0], [18.0, 592.0], [18.1, 593.0], [18.2, 594.0], [18.3, 594.0], [18.4, 595.0], [18.5, 595.0], [18.6, 597.0], [18.7, 598.0], [18.8, 599.0], [18.9, 600.0], [19.0, 601.0], [19.1, 601.0], [19.2, 602.0], [19.3, 604.0], [19.4, 605.0], [19.5, 605.0], [19.6, 605.0], [19.7, 607.0], [19.8, 607.0], [19.9, 609.0], [20.0, 609.0], [20.1, 610.0], [20.2, 611.0], [20.3, 612.0], [20.4, 612.0], [20.5, 612.0], [20.6, 614.0], [20.7, 615.0], [20.8, 616.0], [20.9, 616.0], [21.0, 616.0], [21.1, 619.0], [21.2, 620.0], [21.3, 621.0], [21.4, 622.0], [21.5, 622.0], [21.6, 624.0], [21.7, 625.0], [21.8, 626.0], [21.9, 627.0], [22.0, 628.0], [22.1, 629.0], [22.2, 630.0], [22.3, 632.0], [22.4, 634.0], [22.5, 636.0], [22.6, 637.0], [22.7, 638.0], [22.8, 639.0], [22.9, 640.0], [23.0, 641.0], [23.1, 642.0], [23.2, 643.0], [23.3, 643.0], [23.4, 644.0], [23.5, 645.0], [23.6, 646.0], [23.7, 647.0], [23.8, 647.0], [23.9, 649.0], [24.0, 650.0], [24.1, 651.0], [24.2, 653.0], [24.3, 653.0], [24.4, 654.0], [24.5, 656.0], [24.6, 656.0], [24.7, 658.0], [24.8, 659.0], [24.9, 660.0], [25.0, 661.0], [25.1, 661.0], [25.2, 662.0], [25.3, 664.0], [25.4, 665.0], [25.5, 667.0], [25.6, 669.0], [25.7, 671.0], [25.8, 671.0], [25.9, 672.0], [26.0, 673.0], [26.1, 675.0], [26.2, 675.0], [26.3, 676.0], [26.4, 677.0], [26.5, 678.0], [26.6, 679.0], [26.7, 680.0], [26.8, 681.0], [26.9, 682.0], [27.0, 683.0], [27.1, 684.0], [27.2, 684.0], [27.3, 685.0], [27.4, 685.0], [27.5, 687.0], [27.6, 687.0], [27.7, 689.0], [27.8, 689.0], [27.9, 690.0], [28.0, 691.0], [28.1, 692.0], [28.2, 693.0], [28.3, 693.0], [28.4, 695.0], [28.5, 696.0], [28.6, 697.0], [28.7, 698.0], [28.8, 698.0], [28.9, 700.0], [29.0, 701.0], [29.1, 702.0], [29.2, 702.0], [29.3, 703.0], [29.4, 704.0], [29.5, 706.0], [29.6, 708.0], [29.7, 709.0], [29.8, 710.0], [29.9, 711.0], [30.0, 712.0], [30.1, 713.0], [30.2, 714.0], [30.3, 715.0], [30.4, 716.0], [30.5, 717.0], [30.6, 718.0], [30.7, 719.0], [30.8, 720.0], [30.9, 720.0], [31.0, 722.0], [31.1, 722.0], [31.2, 723.0], [31.3, 724.0], [31.4, 724.0], [31.5, 725.0], [31.6, 725.0], [31.7, 726.0], [31.8, 727.0], [31.9, 728.0], [32.0, 730.0], [32.1, 731.0], [32.2, 732.0], [32.3, 734.0], [32.4, 735.0], [32.5, 736.0], [32.6, 737.0], [32.7, 738.0], [32.8, 738.0], [32.9, 739.0], [33.0, 740.0], [33.1, 740.0], [33.2, 741.0], [33.3, 742.0], [33.4, 742.0], [33.5, 743.0], [33.6, 744.0], [33.7, 744.0], [33.8, 745.0], [33.9, 746.0], [34.0, 746.0], [34.1, 747.0], [34.2, 747.0], [34.3, 748.0], [34.4, 749.0], [34.5, 750.0], [34.6, 750.0], [34.7, 751.0], [34.8, 753.0], [34.9, 753.0], [35.0, 754.0], [35.1, 755.0], [35.2, 756.0], [35.3, 758.0], [35.4, 759.0], [35.5, 760.0], [35.6, 761.0], [35.7, 762.0], [35.8, 762.0], [35.9, 762.0], [36.0, 763.0], [36.1, 764.0], [36.2, 764.0], [36.3, 765.0], [36.4, 766.0], [36.5, 767.0], [36.6, 768.0], [36.7, 770.0], [36.8, 770.0], [36.9, 771.0], [37.0, 773.0], [37.1, 774.0], [37.2, 775.0], [37.3, 776.0], [37.4, 777.0], [37.5, 778.0], [37.6, 779.0], [37.7, 780.0], [37.8, 781.0], [37.9, 782.0], [38.0, 783.0], [38.1, 784.0], [38.2, 784.0], [38.3, 785.0], [38.4, 786.0], [38.5, 787.0], [38.6, 788.0], [38.7, 788.0], [38.8, 789.0], [38.9, 790.0], [39.0, 791.0], [39.1, 792.0], [39.2, 793.0], [39.3, 793.0], [39.4, 794.0], [39.5, 795.0], [39.6, 795.0], [39.7, 796.0], [39.8, 796.0], [39.9, 797.0], [40.0, 798.0], [40.1, 799.0], [40.2, 800.0], [40.3, 802.0], [40.4, 802.0], [40.5, 804.0], [40.6, 805.0], [40.7, 806.0], [40.8, 806.0], [40.9, 807.0], [41.0, 808.0], [41.1, 809.0], [41.2, 810.0], [41.3, 811.0], [41.4, 812.0], [41.5, 813.0], [41.6, 813.0], [41.7, 814.0], [41.8, 815.0], [41.9, 816.0], [42.0, 817.0], [42.1, 817.0], [42.2, 819.0], [42.3, 820.0], [42.4, 820.0], [42.5, 821.0], [42.6, 822.0], [42.7, 823.0], [42.8, 825.0], [42.9, 826.0], [43.0, 827.0], [43.1, 828.0], [43.2, 828.0], [43.3, 828.0], [43.4, 829.0], [43.5, 830.0], [43.6, 830.0], [43.7, 832.0], [43.8, 832.0], [43.9, 833.0], [44.0, 834.0], [44.1, 835.0], [44.2, 836.0], [44.3, 836.0], [44.4, 837.0], [44.5, 839.0], [44.6, 839.0], [44.7, 840.0], [44.8, 841.0], [44.9, 842.0], [45.0, 843.0], [45.1, 844.0], [45.2, 845.0], [45.3, 846.0], [45.4, 846.0], [45.5, 848.0], [45.6, 848.0], [45.7, 849.0], [45.8, 849.0], [45.9, 850.0], [46.0, 851.0], [46.1, 851.0], [46.2, 851.0], [46.3, 853.0], [46.4, 855.0], [46.5, 855.0], [46.6, 856.0], [46.7, 857.0], [46.8, 858.0], [46.9, 859.0], [47.0, 860.0], [47.1, 861.0], [47.2, 861.0], [47.3, 861.0], [47.4, 862.0], [47.5, 862.0], [47.6, 863.0], [47.7, 865.0], [47.8, 865.0], [47.9, 866.0], [48.0, 866.0], [48.1, 867.0], [48.2, 868.0], [48.3, 869.0], [48.4, 869.0], [48.5, 869.0], [48.6, 870.0], [48.7, 871.0], [48.8, 871.0], [48.9, 872.0], [49.0, 873.0], [49.1, 874.0], [49.2, 875.0], [49.3, 875.0], [49.4, 876.0], [49.5, 877.0], [49.6, 877.0], [49.7, 878.0], [49.8, 878.0], [49.9, 880.0], [50.0, 880.0], [50.1, 881.0], [50.2, 882.0], [50.3, 883.0], [50.4, 884.0], [50.5, 886.0], [50.6, 887.0], [50.7, 888.0], [50.8, 888.0], [50.9, 890.0], [51.0, 890.0], [51.1, 892.0], [51.2, 893.0], [51.3, 893.0], [51.4, 894.0], [51.5, 895.0], [51.6, 895.0], [51.7, 896.0], [51.8, 897.0], [51.9, 897.0], [52.0, 898.0], [52.1, 899.0], [52.2, 899.0], [52.3, 900.0], [52.4, 901.0], [52.5, 901.0], [52.6, 902.0], [52.7, 902.0], [52.8, 903.0], [52.9, 904.0], [53.0, 904.0], [53.1, 905.0], [53.2, 905.0], [53.3, 906.0], [53.4, 906.0], [53.5, 908.0], [53.6, 908.0], [53.7, 909.0], [53.8, 910.0], [53.9, 911.0], [54.0, 912.0], [54.1, 913.0], [54.2, 913.0], [54.3, 914.0], [54.4, 914.0], [54.5, 916.0], [54.6, 916.0], [54.7, 917.0], [54.8, 917.0], [54.9, 918.0], [55.0, 919.0], [55.1, 919.0], [55.2, 921.0], [55.3, 921.0], [55.4, 922.0], [55.5, 923.0], [55.6, 923.0], [55.7, 924.0], [55.8, 926.0], [55.9, 927.0], [56.0, 927.0], [56.1, 929.0], [56.2, 929.0], [56.3, 931.0], [56.4, 932.0], [56.5, 932.0], [56.6, 933.0], [56.7, 934.0], [56.8, 935.0], [56.9, 936.0], [57.0, 937.0], [57.1, 938.0], [57.2, 938.0], [57.3, 939.0], [57.4, 941.0], [57.5, 942.0], [57.6, 943.0], [57.7, 944.0], [57.8, 944.0], [57.9, 945.0], [58.0, 946.0], [58.1, 947.0], [58.2, 948.0], [58.3, 949.0], [58.4, 950.0], [58.5, 951.0], [58.6, 951.0], [58.7, 952.0], [58.8, 952.0], [58.9, 953.0], [59.0, 953.0], [59.1, 954.0], [59.2, 956.0], [59.3, 956.0], [59.4, 957.0], [59.5, 958.0], [59.6, 959.0], [59.7, 960.0], [59.8, 962.0], [59.9, 962.0], [60.0, 962.0], [60.1, 964.0], [60.2, 966.0], [60.3, 967.0], [60.4, 968.0], [60.5, 968.0], [60.6, 969.0], [60.7, 970.0], [60.8, 971.0], [60.9, 972.0], [61.0, 972.0], [61.1, 973.0], [61.2, 973.0], [61.3, 974.0], [61.4, 975.0], [61.5, 976.0], [61.6, 977.0], [61.7, 979.0], [61.8, 980.0], [61.9, 981.0], [62.0, 983.0], [62.1, 984.0], [62.2, 985.0], [62.3, 987.0], [62.4, 988.0], [62.5, 989.0], [62.6, 990.0], [62.7, 991.0], [62.8, 993.0], [62.9, 994.0], [63.0, 994.0], [63.1, 996.0], [63.2, 997.0], [63.3, 998.0], [63.4, 999.0], [63.5, 1000.0], [63.6, 1000.0], [63.7, 1003.0], [63.8, 1005.0], [63.9, 1007.0], [64.0, 1009.0], [64.1, 1012.0], [64.2, 1015.0], [64.3, 1019.0], [64.4, 1022.0], [64.5, 1026.0], [64.6, 1027.0], [64.7, 1029.0], [64.8, 1032.0], [64.9, 1034.0], [65.0, 1036.0], [65.1, 1039.0], [65.2, 1041.0], [65.3, 1045.0], [65.4, 1048.0], [65.5, 1050.0], [65.6, 1055.0], [65.7, 1058.0], [65.8, 1059.0], [65.9, 1062.0], [66.0, 1064.0], [66.1, 1067.0], [66.2, 1068.0], [66.3, 1071.0], [66.4, 1072.0], [66.5, 1073.0], [66.6, 1076.0], [66.7, 1079.0], [66.8, 1081.0], [66.9, 1085.0], [67.0, 1090.0], [67.1, 1092.0], [67.2, 1095.0], [67.3, 1096.0], [67.4, 1099.0], [67.5, 1104.0], [67.6, 1106.0], [67.7, 1110.0], [67.8, 1111.0], [67.9, 1113.0], [68.0, 1115.0], [68.1, 1117.0], [68.2, 1118.0], [68.3, 1121.0], [68.4, 1122.0], [68.5, 1124.0], [68.6, 1126.0], [68.7, 1129.0], [68.8, 1131.0], [68.9, 1135.0], [69.0, 1137.0], [69.1, 1139.0], [69.2, 1141.0], [69.3, 1142.0], [69.4, 1143.0], [69.5, 1147.0], [69.6, 1149.0], [69.7, 1151.0], [69.8, 1153.0], [69.9, 1154.0], [70.0, 1157.0], [70.1, 1159.0], [70.2, 1160.0], [70.3, 1161.0], [70.4, 1163.0], [70.5, 1165.0], [70.6, 1168.0], [70.7, 1171.0], [70.8, 1172.0], [70.9, 1176.0], [71.0, 1178.0], [71.1, 1181.0], [71.2, 1183.0], [71.3, 1187.0], [71.4, 1188.0], [71.5, 1189.0], [71.6, 1191.0], [71.7, 1195.0], [71.8, 1197.0], [71.9, 1198.0], [72.0, 1200.0], [72.1, 1200.0], [72.2, 1203.0], [72.3, 1206.0], [72.4, 1208.0], [72.5, 1212.0], [72.6, 1214.0], [72.7, 1218.0], [72.8, 1221.0], [72.9, 1223.0], [73.0, 1226.0], [73.1, 1228.0], [73.2, 1230.0], [73.3, 1234.0], [73.4, 1234.0], [73.5, 1237.0], [73.6, 1239.0], [73.7, 1241.0], [73.8, 1243.0], [73.9, 1245.0], [74.0, 1248.0], [74.1, 1250.0], [74.2, 1253.0], [74.3, 1256.0], [74.4, 1261.0], [74.5, 1264.0], [74.6, 1267.0], [74.7, 1268.0], [74.8, 1271.0], [74.9, 1272.0], [75.0, 1274.0], [75.1, 1275.0], [75.2, 1277.0], [75.3, 1278.0], [75.4, 1280.0], [75.5, 1283.0], [75.6, 1286.0], [75.7, 1288.0], [75.8, 1290.0], [75.9, 1294.0], [76.0, 1295.0], [76.1, 1297.0], [76.2, 1299.0], [76.3, 1300.0], [76.4, 1302.0], [76.5, 1304.0], [76.6, 1305.0], [76.7, 1311.0], [76.8, 1312.0], [76.9, 1313.0], [77.0, 1316.0], [77.1, 1318.0], [77.2, 1319.0], [77.3, 1320.0], [77.4, 1321.0], [77.5, 1324.0], [77.6, 1325.0], [77.7, 1328.0], [77.8, 1332.0], [77.9, 1334.0], [78.0, 1340.0], [78.1, 1342.0], [78.2, 1345.0], [78.3, 1348.0], [78.4, 1349.0], [78.5, 1351.0], [78.6, 1352.0], [78.7, 1355.0], [78.8, 1356.0], [78.9, 1358.0], [79.0, 1359.0], [79.1, 1361.0], [79.2, 1362.0], [79.3, 1365.0], [79.4, 1367.0], [79.5, 1369.0], [79.6, 1370.0], [79.7, 1375.0], [79.8, 1379.0], [79.9, 1381.0], [80.0, 1383.0], [80.1, 1384.0], [80.2, 1387.0], [80.3, 1389.0], [80.4, 1390.0], [80.5, 1392.0], [80.6, 1395.0], [80.7, 1396.0], [80.8, 1398.0], [80.9, 1401.0], [81.0, 1402.0], [81.1, 1407.0], [81.2, 1409.0], [81.3, 1413.0], [81.4, 1415.0], [81.5, 1417.0], [81.6, 1418.0], [81.7, 1419.0], [81.8, 1420.0], [81.9, 1422.0], [82.0, 1424.0], [82.1, 1426.0], [82.2, 1428.0], [82.3, 1430.0], [82.4, 1431.0], [82.5, 1431.0], [82.6, 1433.0], [82.7, 1437.0], [82.8, 1441.0], [82.9, 1442.0], [83.0, 1447.0], [83.1, 1449.0], [83.2, 1450.0], [83.3, 1452.0], [83.4, 1453.0], [83.5, 1455.0], [83.6, 1456.0], [83.7, 1458.0], [83.8, 1460.0], [83.9, 1462.0], [84.0, 1463.0], [84.1, 1468.0], [84.2, 1471.0], [84.3, 1475.0], [84.4, 1479.0], [84.5, 1480.0], [84.6, 1482.0], [84.7, 1486.0], [84.8, 1487.0], [84.9, 1490.0], [85.0, 1492.0], [85.1, 1493.0], [85.2, 1497.0], [85.3, 1500.0], [85.4, 1504.0], [85.5, 1505.0], [85.6, 1508.0], [85.7, 1510.0], [85.8, 1512.0], [85.9, 1514.0], [86.0, 1517.0], [86.1, 1520.0], [86.2, 1523.0], [86.3, 1525.0], [86.4, 1527.0], [86.5, 1529.0], [86.6, 1531.0], [86.7, 1535.0], [86.8, 1535.0], [86.9, 1538.0], [87.0, 1542.0], [87.1, 1544.0], [87.2, 1549.0], [87.3, 1553.0], [87.4, 1558.0], [87.5, 1564.0], [87.6, 1568.0], [87.7, 1572.0], [87.8, 1576.0], [87.9, 1580.0], [88.0, 1583.0], [88.1, 1587.0], [88.2, 1590.0], [88.3, 1595.0], [88.4, 1598.0], [88.5, 1604.0], [88.6, 1608.0], [88.7, 1613.0], [88.8, 1616.0], [88.9, 1622.0], [89.0, 1624.0], [89.1, 1635.0], [89.2, 1639.0], [89.3, 1643.0], [89.4, 1648.0], [89.5, 1652.0], [89.6, 1656.0], [89.7, 1661.0], [89.8, 1665.0], [89.9, 1672.0], [90.0, 1679.0], [90.1, 1681.0], [90.2, 1687.0], [90.3, 1692.0], [90.4, 1696.0], [90.5, 1699.0], [90.6, 1703.0], [90.7, 1709.0], [90.8, 1715.0], [90.9, 1718.0], [91.0, 1721.0], [91.1, 1726.0], [91.2, 1732.0], [91.3, 1738.0], [91.4, 1742.0], [91.5, 1744.0], [91.6, 1748.0], [91.7, 1755.0], [91.8, 1760.0], [91.9, 1764.0], [92.0, 1767.0], [92.1, 1771.0], [92.2, 1772.0], [92.3, 1778.0], [92.4, 1784.0], [92.5, 1790.0], [92.6, 1795.0], [92.7, 1798.0], [92.8, 1802.0], [92.9, 1806.0], [93.0, 1813.0], [93.1, 1819.0], [93.2, 1822.0], [93.3, 1824.0], [93.4, 1829.0], [93.5, 1833.0], [93.6, 1840.0], [93.7, 1842.0], [93.8, 1845.0], [93.9, 1851.0], [94.0, 1858.0], [94.1, 1864.0], [94.2, 1870.0], [94.3, 1876.0], [94.4, 1886.0], [94.5, 1901.0], [94.6, 1918.0], [94.7, 1931.0], [94.8, 1935.0], [94.9, 1945.0], [95.0, 1965.0], [95.1, 1977.0], [95.2, 1996.0], [95.3, 2012.0], [95.4, 2020.0], [95.5, 2037.0], [95.6, 2050.0], [95.7, 2060.0], [95.8, 2082.0], [95.9, 2092.0], [96.0, 2106.0], [96.1, 2124.0], [96.2, 2139.0], [96.3, 2148.0], [96.4, 2157.0], [96.5, 2168.0], [96.6, 2176.0], [96.7, 2192.0], [96.8, 2206.0], [96.9, 2221.0], [97.0, 2253.0], [97.1, 2292.0], [97.2, 2357.0], [97.3, 2392.0], [97.4, 2413.0], [97.5, 2457.0], [97.6, 2522.0], [97.7, 2581.0], [97.8, 2692.0], [97.9, 2783.0], [98.0, 3033.0], [98.1, 3259.0], [98.2, 5498.0], [98.3, 5588.0], [98.4, 5636.0], [98.5, 5743.0], [98.6, 5824.0], [98.7, 5894.0], [98.8, 6009.0], [98.9, 6173.0], [99.0, 6362.0], [99.1, 6521.0], [99.2, 6753.0], [99.3, 6822.0], [99.4, 6888.0], [99.5, 6977.0], [99.6, 7006.0], [99.7, 7044.0], [99.8, 7106.0], [99.9, 7302.0]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "maxX": 100.0, "title": "Response Time Percentiles"}},
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
        data: {"result": {"minY": 1.0, "minX": 0.0, "maxY": 670.0, "series": [{"data": [[0.0, 8.0], [600.0, 555.0], [700.0, 623.0], [800.0, 670.0], [900.0, 623.0], [1000.0, 218.0], [1100.0, 254.0], [1200.0, 237.0], [1300.0, 254.0], [1400.0, 244.0], [1500.0, 174.0], [1600.0, 116.0], [1700.0, 125.0], [1800.0, 96.0], [1900.0, 42.0], [2000.0, 41.0], [2100.0, 42.0], [2200.0, 21.0], [2300.0, 10.0], [2400.0, 15.0], [2500.0, 7.0], [2600.0, 6.0], [2700.0, 6.0], [2800.0, 1.0], [2900.0, 2.0], [3000.0, 2.0], [3100.0, 4.0], [3200.0, 1.0], [3400.0, 1.0], [3500.0, 2.0], [3600.0, 2.0], [5600.0, 9.0], [5400.0, 1.0], [5500.0, 6.0], [5800.0, 7.0], [5700.0, 5.0], [5900.0, 5.0], [6000.0, 3.0], [6100.0, 5.0], [6300.0, 3.0], [6200.0, 2.0], [6400.0, 3.0], [6500.0, 3.0], [6800.0, 9.0], [6700.0, 7.0], [6900.0, 7.0], [7000.0, 13.0], [7100.0, 2.0], [7200.0, 4.0], [7300.0, 3.0], [9100.0, 1.0], [8900.0, 1.0], [10200.0, 1.0], [100.0, 8.0], [200.0, 44.0], [300.0, 142.0], [400.0, 287.0], [500.0, 556.0]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 100, "maxX": 10200.0, "title": "Response Time Distribution"}},
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
        data: {"result": {"minY": 3.0, "minX": 2.0, "ticks": [[0, "Requests having \nresponse time <= 500ms"], [1, "Requests having \nresponse time > 500ms and <= 1,500ms"], [2, "Requests having \nresponse time > 1,500ms"], [3, "Requests in error"]], "maxY": 5536.0, "series": [{"data": [], "color": "#9ACD32", "isOverall": false, "label": "Requests having \nresponse time <= 500ms", "isController": false}, {"data": [], "color": "yellow", "isOverall": false, "label": "Requests having \nresponse time > 500ms and <= 1,500ms", "isController": false}, {"data": [[2.0, 3.0]], "color": "orange", "isOverall": false, "label": "Requests having \nresponse time > 1,500ms", "isController": false}, {"data": [[3.0, 5536.0]], "color": "#FF6347", "isOverall": false, "label": "Requests in error", "isController": false}], "supportsControllersDiscrimination": false, "maxX": 3.0, "title": "Synthetic Response Times Distribution"}},
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
        data: {"result": {"minY": 97.44507772020724, "minX": 1.76286792E12, "maxY": 100.0, "series": [{"data": [[1.76286792E12, 100.0], [1.76286798E12, 97.44507772020724]], "isOverall": false, "label": "Gradual Load Test", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76286798E12, "title": "Active Threads Over Time"}},
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
        data: {"result": {"minY": 523.0, "minX": 2.0, "maxY": 2500.0, "series": [{"data": [[2.0, 1166.0], [3.0, 929.0], [4.0, 1639.0], [5.0, 1365.0], [6.0, 936.0], [7.0, 1282.0], [8.0, 2415.0], [9.0, 1382.0], [10.0, 912.0], [11.0, 1840.0], [12.0, 1366.0], [13.0, 1317.0], [14.0, 2153.0], [15.0, 1319.0], [16.0, 1328.0], [17.0, 1814.0], [18.0, 1426.0], [19.0, 846.0], [21.0, 1793.0], [23.0, 1143.0], [24.0, 1409.0], [25.0, 2176.0], [26.0, 1395.0], [28.0, 1639.0], [29.0, 835.0], [30.0, 866.0], [31.0, 1859.0], [33.0, 1141.0], [32.0, 987.0], [35.0, 849.0], [34.0, 1449.0], [37.0, 923.0], [36.0, 840.0], [39.0, 1381.0], [38.0, 813.0], [41.0, 1390.0], [40.0, 893.0], [43.0, 1426.0], [42.0, 1888.0], [45.0, 1436.0], [44.0, 2141.0], [49.0, 937.0], [48.0, 1548.6666666666667], [50.0, 1485.0], [53.0, 1687.6666666666667], [55.0, 997.0], [54.0, 1496.0], [57.0, 1462.0], [56.0, 952.0], [59.0, 1537.0], [58.0, 2172.0], [61.0, 1022.0], [60.0, 2500.0], [63.0, 1535.0], [62.0, 1507.0], [67.0, 1531.0], [66.0, 1514.0], [65.0, 1870.0], [64.0, 1470.0], [70.0, 1522.0], [68.0, 1606.0], [75.0, 1036.0], [74.0, 1160.5], [72.0, 1491.5], [79.0, 523.0], [78.0, 1081.0], [77.0, 1489.0], [76.0, 1036.0], [83.0, 1071.0], [82.0, 1773.5], [80.0, 1503.0], [87.0, 1493.0], [86.0, 2301.0], [85.0, 1764.0], [84.0, 544.0], [91.0, 2056.0], [88.0, 1726.0], [95.0, 1649.0], [94.0, 1093.0], [93.0, 1977.0], [92.0, 1089.0], [99.0, 1098.0], [97.0, 1035.0], [100.0, 1071.7832720588235]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}, {"data": [[99.10976710597578, 1078.0565083950173]], "isOverall": false, "label": "Complete Reservation (Saga Test)-Aggregated", "isController": false}], "supportsControllersDiscrimination": true, "maxX": 100.0, "title": "Time VS Threads"}},
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
        data : {"result": {"minY": 7173.166666666667, "minX": 1.76286792E12, "maxY": 31518.6, "series": [{"data": [[1.76286792E12, 13441.45], [1.76286798E12, 7173.166666666667]], "isOverall": false, "label": "Bytes received per second", "isController": false}, {"data": [[1.76286792E12, 31518.6], [1.76286798E12, 16855.333333333332]], "isOverall": false, "label": "Bytes sent per second", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76286798E12, "title": "Bytes Throughput Over Time"}},
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
        data: {"result": {"minY": 1039.2111388196167, "minX": 1.76286792E12, "maxY": 1150.6953367875617, "series": [{"data": [[1.76286792E12, 1039.2111388196167], [1.76286798E12, 1150.6953367875617]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76286798E12, "title": "Response Time Over Time"}},
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
        data: {"result": {"minY": 1004.4070379606527, "minX": 1.76286792E12, "maxY": 1078.46683937824, "series": [{"data": [[1.76286792E12, 1004.4070379606527], [1.76286798E12, 1078.46683937824]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76286798E12, "title": "Latencies Over Time"}},
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
        data: {"result": {"minY": 2.403626943005182, "minX": 1.76286792E12, "maxY": 2.828761429758936, "series": [{"data": [[1.76286792E12, 2.828761429758936], [1.76286798E12, 2.403626943005182]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76286798E12, "title": "Connect Time Over Time"}},
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
        data: {"result": {"minY": 8908.0, "minX": 1.76286792E12, "maxY": 10225.0, "series": [{"data": [[1.76286792E12, 10225.0]], "isOverall": false, "label": "Max", "isController": false}, {"data": [[1.76286792E12, 10225.0]], "isOverall": false, "label": "90th percentile", "isController": false}, {"data": [[1.76286792E12, 10225.0]], "isOverall": false, "label": "99th percentile", "isController": false}, {"data": [[1.76286792E12, 10225.0]], "isOverall": false, "label": "95th percentile", "isController": false}, {"data": [[1.76286792E12, 8908.0]], "isOverall": false, "label": "Min", "isController": false}, {"data": [[1.76286792E12, 9153.0]], "isOverall": false, "label": "Median", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76286792E12, "title": "Response Time Percentiles Over Time (successful requests only)"}},
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
    data: {"result": {"minY": 645.0, "minX": 11.0, "maxY": 9153.0, "series": [{"data": [[145.0, 9153.0]], "isOverall": false, "label": "Successes", "isController": false}, {"data": [[39.0, 1587.0], [53.0, 1858.0], [55.0, 1434.0], [66.0, 926.5], [64.0, 1007.5], [71.0, 1089.0], [73.0, 695.0], [72.0, 1056.5], [77.0, 1210.0], [78.0, 1266.0], [80.0, 960.5], [84.0, 1333.0], [90.0, 1518.5], [89.0, 907.0], [91.0, 953.0], [95.0, 1073.5], [94.0, 1312.0], [93.0, 942.0], [97.0, 861.0], [99.0, 866.0], [101.0, 1426.0], [106.0, 953.5], [105.0, 869.0], [104.0, 937.5], [109.0, 943.0], [108.0, 906.0], [115.0, 899.0], [116.0, 938.5], [118.0, 768.0], [120.0, 868.0], [127.0, 703.0], [132.0, 645.0], [128.0, 746.0], [135.0, 783.0], [137.0, 676.0], [145.0, 773.0], [147.0, 713.0], [150.0, 762.0], [146.0, 700.0], [153.0, 699.0], [155.0, 676.0], [11.0, 1365.0]], "isOverall": false, "label": "Failures", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 1000, "maxX": 155.0, "title": "Response Time Vs Request"}},
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
    data: {"result": {"minY": 644.5, "minX": 11.0, "maxY": 8983.0, "series": [{"data": [[145.0, 8983.0]], "isOverall": false, "label": "Successes", "isController": false}, {"data": [[39.0, 1320.0], [53.0, 1821.0], [55.0, 1299.0], [66.0, 926.5], [64.0, 1006.5], [71.0, 1088.0], [73.0, 695.0], [72.0, 1049.0], [77.0, 1161.0], [78.0, 1195.0], [80.0, 959.5], [84.0, 1326.5], [90.0, 1298.5], [89.0, 906.0], [91.0, 951.0], [95.0, 1018.5], [94.0, 1265.0], [93.0, 941.0], [97.0, 860.5], [99.0, 866.0], [101.0, 1381.0], [106.0, 953.5], [105.0, 869.0], [104.0, 937.0], [109.0, 943.0], [108.0, 906.0], [115.0, 898.5], [116.0, 937.5], [118.0, 767.0], [120.0, 868.0], [127.0, 703.0], [132.0, 644.5], [128.0, 745.5], [135.0, 782.0], [137.0, 676.0], [145.0, 773.0], [147.0, 713.0], [150.0, 761.5], [146.0, 700.0], [153.0, 699.0], [155.0, 675.0], [11.0, 1364.0]], "isOverall": false, "label": "Failures", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 1000, "maxX": 155.0, "title": "Latencies Vs Request"}},
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
        data: {"result": {"minY": 30.5, "minX": 1.76286792E12, "maxY": 61.81666666666667, "series": [{"data": [[1.76286792E12, 61.81666666666667], [1.76286798E12, 30.5]], "isOverall": false, "label": "hitsPerSecond", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76286798E12, "title": "Hits Per Second"}},
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
        data: {"result": {"minY": 0.05, "minX": 1.76286792E12, "maxY": 60.1, "series": [{"data": [[1.76286792E12, 0.05]], "isOverall": false, "label": "200", "isController": false}, {"data": [[1.76286792E12, 60.1], [1.76286798E12, 32.166666666666664]], "isOverall": false, "label": "400", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76286798E12, "title": "Codes Per Second"}},
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
        data: {"result": {"minY": 0.05, "minX": 1.76286792E12, "maxY": 60.1, "series": [{"data": [[1.76286792E12, 60.1], [1.76286798E12, 32.166666666666664]], "isOverall": false, "label": "Complete Reservation (Saga Test)-failure", "isController": false}, {"data": [[1.76286792E12, 0.05]], "isOverall": false, "label": "Complete Reservation (Saga Test)-success", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76286798E12, "title": "Transactions Per Second"}},
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
        data: {"result": {"minY": 0.05, "minX": 1.76286792E12, "maxY": 60.1, "series": [{"data": [[1.76286792E12, 0.05]], "isOverall": false, "label": "Transaction-success", "isController": false}, {"data": [[1.76286792E12, 60.1], [1.76286798E12, 32.166666666666664]], "isOverall": false, "label": "Transaction-failure", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76286798E12, "title": "Total Transactions Per Second"}},
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

