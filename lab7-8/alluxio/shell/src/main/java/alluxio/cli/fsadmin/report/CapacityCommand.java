/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.cli.fsadmin.report;

import alluxio.cli.fsadmin.FileSystemAdminShellUtils;
import alluxio.cli.fsadmin.command.ReportCommand;
import alluxio.client.block.AllMastersWorkerInfo;
import alluxio.client.block.BlockMasterClient;
import alluxio.client.block.options.GetWorkerReportOptions;
import alluxio.client.block.options.GetWorkerReportOptions.WorkerInfoField;
import alluxio.client.block.options.GetWorkerReportOptions.WorkerRange;
import alluxio.client.block.util.WorkerInfoUtil;
import alluxio.collections.Pair;
import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.status.InvalidArgumentException;
import alluxio.grpc.Scope;
import alluxio.util.FormatUtils;
import alluxio.wire.WorkerInfo;

import com.google.common.base.Strings;
import org.apache.commons.cli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Prints Alluxio capacity information.
 */
public class CapacityCommand {
  private static final int INDENT_SIZE = 4;

  private BlockMasterClient mBlockMasterClient;
  private PrintStream mPrintStream;
  private int mIndentationLevel = 0;
  private long mSumCapacityBytes;
  private long mSumUsedBytes;
  private int mSumNumVCpus;
  private Map<String, Long> mSumCapacityBytesOnTierMap;
  private Map<String, Long> mSumUsedBytesOnTierMap;
  private TreeMap<String, Map<String, String>> mCapacityTierInfoMap;
  private Map<String, Map<String, String>> mUsedTierInfoMap;

  private static final String LIVE_WORKER_STATE = "In Service";
  private static final String LOST_WORKER_STATE = "Out of Service";

  /**
   * Creates a new instance of {@link CapacityCommand}.
   *
   * @param blockMasterClient client to connect to block master
   * @param printStream stream to print summary information to
   */
  public CapacityCommand(BlockMasterClient blockMasterClient, PrintStream printStream) {
    mBlockMasterClient = blockMasterClient;
    mPrintStream = printStream;
  }

  /**
   * Runs report capacity command.
   *
   * @param cl CommandLine to get client options
   * @return 0 on success, 1 otherwise
   */
  public int run(CommandLine cl) throws IOException {
    if (cl.hasOption(ReportCommand.HELP_OPTION_NAME)) {
      System.out.println(getUsage());
      return 0;
    }

    GetWorkerReportOptions options = getOptions(cl);
    Configuration.loadClusterDefaults(mBlockMasterClient.getConfAddress(), Scope.CLIENT);
    generateCapacityReport(options, Configuration.global());
    return 0;
  }

  /**
   * Generates capacity report.
   *
   * @param options GetWorkerReportOptions to get worker report
   * @param conf the cluster configuration
   */
  public void generateCapacityReport(GetWorkerReportOptions options, AlluxioConfiguration conf)
      throws IOException {
    boolean workerRegisterToAllMasters =
        conf.getBoolean(PropertyKey.WORKER_REGISTER_TO_ALL_MASTERS);

    final List<WorkerInfo> workerInfoList;
    final AllMastersWorkerInfo allMastersWorkerInfo;
    if (workerRegisterToAllMasters) {
      allMastersWorkerInfo =
          WorkerInfoUtil.getWorkerReportsFromAllMasters(
          conf, mBlockMasterClient, options);
      workerInfoList = allMastersWorkerInfo.getPrimaryMasterWorkerInfo();
    } else {
      allMastersWorkerInfo = null;
      workerInfoList = mBlockMasterClient.getWorkerReport(options);
    }
    if (workerInfoList.size() == 0) {
      print("No workers found.");
      return;
    }
    Collections.sort(workerInfoList, new WorkerInfo.LastContactSecComparator());

    collectWorkerInfo(workerInfoList);
    printAggregatedInfo(options);
    printWorkerInfo(workerInfoList);
    if (workerRegisterToAllMasters) {
      printWorkerAllMasterConnectionInfo(allMastersWorkerInfo);
    }
  }

  /**
   * Collects worker capacity information.
   *
   * @param workerInfoList the worker info list to collect info from
   */
  private void collectWorkerInfo(List<WorkerInfo> workerInfoList) {
    initVariables();
    for (WorkerInfo workerInfo : workerInfoList) {
      long usedBytes = workerInfo.getUsedBytes();
      long capacityBytes = workerInfo.getCapacityBytes();
      int vCpu = workerInfo.getNumVCpu();
      mSumCapacityBytes += capacityBytes;
      mSumUsedBytes += usedBytes;
      mSumNumVCpus += vCpu;

      String workerName = workerInfo.getAddress().getHost();

      Map<String, Long> totalBytesOnTiers = workerInfo.getCapacityBytesOnTiers();
      for (Map.Entry<String, Long> totalBytesTier : totalBytesOnTiers.entrySet()) {
        String tier = totalBytesTier.getKey();
        long value = totalBytesTier.getValue();
        mSumCapacityBytesOnTierMap.put(tier,
            value + mSumCapacityBytesOnTierMap.getOrDefault(tier, 0L));

        Map<String, String> map = mCapacityTierInfoMap.getOrDefault(tier, new HashMap<>());
        map.put(workerName, FormatUtils.getSizeFromBytes(value));
        mCapacityTierInfoMap.put(tier, map);
      }

      Map<String, Long> usedBytesOnTiers = workerInfo.getUsedBytesOnTiers();
      for (Map.Entry<String, Long> usedBytesTier: usedBytesOnTiers.entrySet()) {
        String tier = usedBytesTier.getKey();
        long value = usedBytesTier.getValue();
        mSumUsedBytesOnTierMap.put(tier,
            value + mSumUsedBytesOnTierMap.getOrDefault(tier, 0L));

        Map<String, String> map = mUsedTierInfoMap.getOrDefault(tier, new HashMap<>());
        map.put(workerName, FormatUtils.getSizeFromBytes(value));
        mUsedTierInfoMap.put(tier, map);
      }
    }
  }

  /**
   * Prints aggregated worker capacity information.
   *
   * @param options GetWorkerReportOptions to check if input is invalid
   */
  private void printAggregatedInfo(GetWorkerReportOptions options) {
    mIndentationLevel = 0;
    print(String.format("Capacity information for %s workers: ",
        options.getWorkerRange().toString().toLowerCase()));

    mIndentationLevel++;
    print("Total vCPUs: " + mSumNumVCpus);
    print("Total Capacity: " + FormatUtils.getSizeFromBytes(mSumCapacityBytes));
    mIndentationLevel++;
    for (Map.Entry<String, Long> totalBytesTier : mSumCapacityBytesOnTierMap.entrySet()) {
      long value = totalBytesTier.getValue();
      print("Tier: " + totalBytesTier.getKey()
          + "  Size: " + FormatUtils.getSizeFromBytes(value));
    }
    mIndentationLevel--;

    print("Used Capacity: "
        + FormatUtils.getSizeFromBytes(mSumUsedBytes));
    mIndentationLevel++;
    for (Map.Entry<String, Long> usedBytesTier : mSumUsedBytesOnTierMap.entrySet()) {
      long value = usedBytesTier.getValue();
      print("Tier: " + usedBytesTier.getKey()
          + "  Size: " + FormatUtils.getSizeFromBytes(value));
    }
    mIndentationLevel--;

    if (mSumCapacityBytes != 0) {
      int usedPercentage = (int) (100L * mSumUsedBytes / mSumCapacityBytes);
      print(String.format("Used Percentage: " + "%s%%", usedPercentage));
      print(String.format("Free Percentage: " + "%s%%", 100 - usedPercentage));
    }
  }

  private String getMasterAddressesString(Set<java.net.InetSocketAddress> addresses) {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    List<String> addressStrings =
        addresses.stream().map(it -> masterAddressToString(it, addresses)).sorted().collect(
            Collectors.toList());
    for (int i = 0; i < addressStrings.size(); ++i) {
      sb.append(addressStrings.get(i));
      if (i != addressStrings.size() - 1) {
        sb.append(",");
      }
    }
    sb.append("]");
    return sb.toString();
  }

  private String masterAddressToString(
      InetSocketAddress inetSocketAddress,
      Collection<InetSocketAddress> masterAddresses) {
    // If multiple masters share the same host name, we will display the host name + port
    // otherwise just the host name.
    if (inetSocketAddress.getHostName().equals("localhost") || masterAddresses.stream()
        .filter(it -> it.getHostName().equals(inetSocketAddress.getHostName())).count() > 1) {
      return inetSocketAddress.toString();
    }
    return inetSocketAddress.getHostName();
  }

  private void printWorkerAllMasterConnectionInfo(
      AllMastersWorkerInfo allMastersWorkerInfo) {
    List<InetSocketAddress> masterAddresses = allMastersWorkerInfo.getMasterAddresses();
    int maxWorkerNameLength =
        allMastersWorkerInfo.getWorkerIdAddressMap().values().stream()
            .map(w -> w.getHostName().length())
            .max(Comparator.comparing(Integer::intValue)).orElse(0);

    int workerNameIndent = 16;
    if (workerNameIndent <= maxWorkerNameLength) {
      // extend first indent according to the longest worker name by default 5
      workerNameIndent = maxWorkerNameLength + 5;
    }

    // Create indentation to tolerate 2 unregistered masters
    int maxMasterNameLength =
        allMastersWorkerInfo.getWorkerIdAddressMap().values().stream()
            .map(w -> masterAddressToString(w, masterAddresses).length())
            .max(Comparator.comparing(Integer::intValue)).orElse(0);
    int unregisteredMasterNameIndent = Math.max(24, maxMasterNameLength * 2 + 10);
    String format = "%-" + workerNameIndent
        + "s %-" + unregisteredMasterNameIndent + "s %-" + unregisteredMasterNameIndent + "s %s";
    print("");
    print(String.format(format, "Worker Name", "Not Registered With", "Lost", "In Service"));
    for (Map.Entry<Long, List<Pair<InetSocketAddress, WorkerInfo>>> workerInfoEntry :
        allMastersWorkerInfo.getWorkerIdInfoMap().entrySet()) {
      if (workerInfoEntry.getValue().stream()
          .noneMatch(it -> it.getSecond().getState().equals("In Service"))) {
        // Don't display the worker if it has been removed from all masters.
        continue;
      }
      long workerId = workerInfoEntry.getKey();
      InetSocketAddress workerAddress = allMastersWorkerInfo.getWorkerIdAddressMap()
          .get(workerId);
      String workerName = workerAddress != null ? workerAddress.getHostName()
          : "(UNKNOWN, id = " + workerId + ")";
      Set<InetSocketAddress> inServiceMasters =
          workerInfoEntry.getValue().stream()
              .filter(it -> it.getSecond().getState().equals(LIVE_WORKER_STATE))
              .map(alluxio.collections.Pair::getFirst).collect(Collectors.toSet());
      Set<InetSocketAddress> lostMasters =
          workerInfoEntry.getValue().stream()
              .filter(it -> it.getSecond().getState().equals(LOST_WORKER_STATE))
              .map(alluxio.collections.Pair::getFirst).collect(Collectors.toSet());
      Set<InetSocketAddress> allMasterAddresses =
          new HashSet<>(allMastersWorkerInfo.getMasterAddresses());
      Set<InetSocketAddress> notRegisteredMaster =
          com.google.common.collect.Sets.difference(allMasterAddresses,
              com.google.common.collect.Sets.union(inServiceMasters, lostMasters));
      print(String.format(format, workerName, getMasterAddressesString(notRegisteredMaster),
          getMasterAddressesString(lostMasters), getMasterAddressesString(inServiceMasters)));
    }
  }

  /**
   * Prints worker capacity information.
   *
   * @param workerInfoList the worker info list to get info from
   */
  private void printWorkerInfo(List<WorkerInfo> workerInfoList) {
    mIndentationLevel = 0;
    if (mCapacityTierInfoMap.size() == 0) {
      return;
    } else if (mCapacityTierInfoMap.size() == 1) {
      // TODO(jiacheng): test BOTH long and short output
      // Do not print Total value when only one tier exists
      printShortWorkerInfo(workerInfoList);
      return;
    }
    Set<String> tiers = mCapacityTierInfoMap.keySet();
    String tiersInfo = String.format(Strings.repeat("%-14s", tiers.size()), tiers.toArray());
    String longInfoFormat = getInfoFormat(workerInfoList, false);
    print(String.format("%n" + longInfoFormat,
        "Worker Name", "State", "Last Heartbeat", "Storage", "Total", tiersInfo,
        "Version", "Revision"));

    for (WorkerInfo info : workerInfoList) {
      String workerName = info.getAddress().getHost();

      long usedBytes = info.getUsedBytes();
      long capacityBytes = info.getCapacityBytes();

      String usedPercentageInfo = "";
      if (capacityBytes != 0) {
        int usedPercentage = (int) (100L * usedBytes / capacityBytes);
        usedPercentageInfo = String.format(" (%s%%)", usedPercentage);
      }

      String capacityTierInfo = getWorkerFormattedTierValues(mCapacityTierInfoMap, workerName);
      String usedTierInfo = getWorkerFormattedTierValues(mUsedTierInfoMap, workerName);

      print(String.format(longInfoFormat, workerName, info.getState(),
          info.getLastContactSec(), "capacity",
          FormatUtils.getSizeFromBytes(capacityBytes), capacityTierInfo,
          info.getVersion(), info.getRevision()));
      print(String.format(longInfoFormat, "", "", "", "used",
          FormatUtils.getSizeFromBytes(usedBytes) + usedPercentageInfo, usedTierInfo,
          "", ""));
    }
  }

  /**
   * Prints worker information when only one tier exists.
   *
   * @param workerInfoList the worker info list to get info from
   */
  private void printShortWorkerInfo(List<WorkerInfo> workerInfoList) {
    String tier = String.format("%-16s", mCapacityTierInfoMap.firstKey());
    String shortInfoFormat = getInfoFormat(workerInfoList, true);
    print(String.format("%n" + shortInfoFormat,
        "Worker Name", "State", "Last Heartbeat", "Storage", tier, "Version", "Revision"));

    for (WorkerInfo info : workerInfoList) {
      long capacityBytes = info.getCapacityBytes();
      long usedBytes = info.getUsedBytes();

      String usedPercentageInfo = "";
      if (capacityBytes != 0) {
        int usedPercentage = (int) (100L * usedBytes / capacityBytes);
        usedPercentageInfo = String.format(" (%s%%)", usedPercentage);
      }
      print(String.format(shortInfoFormat, info.getAddress().getHost(), info.getState(),
          info.getLastContactSec(), "capacity",
          String.format("%-16s", FormatUtils.getSizeFromBytes(capacityBytes)),
          info.getVersion(), info.getRevision()));
      print(String.format(shortInfoFormat, "", "", "", "used",
          String.format("%-16s", FormatUtils.getSizeFromBytes(usedBytes) + usedPercentageInfo),
          "", ""));
    }
  }

  /**
   * Gets the info format according to the longest worker name.
   * @param workerInfoList the worker info list to get info from
   * @param isShort whether exists only one tier
   * @return the info format for printing long/short worker info
   */
  private String getInfoFormat(List<WorkerInfo> workerInfoList, boolean isShort) {
    int maxWorkerNameLength = workerInfoList.stream().map(w -> w.getAddress().getHost().length())
        .max(Comparator.comparing(Integer::intValue)).get();
    int firstIndent = 16;
    if (firstIndent <= maxWorkerNameLength) {
      // extend first indent according to the longest worker name by default 5
      firstIndent = maxWorkerNameLength + 5;
    }
    if (isShort) {
      return "%-" + firstIndent + "s %-15s %-16s %-13s %s %-16s %-40s";
    }
    return "%-" + firstIndent + "s %-15s %-16s %-13s %-16s %s %-16s %-40s";
  }

  /**
   * Gets the worker info options.
   *
   * @param cl CommandLine that contains the client options
   * @return GetWorkerReportOptions to get worker information
   */
  private GetWorkerReportOptions getOptions(CommandLine cl) throws IOException {
    if (cl.getOptions().length > 1) {
      System.out.println(getUsage());
      throw new InvalidArgumentException("Too many arguments passed in.");
    }
    GetWorkerReportOptions workerOptions = GetWorkerReportOptions.defaults();

    Set<WorkerInfoField> fieldRange = EnumSet.of(WorkerInfoField.ADDRESS,
        WorkerInfoField.WORKER_CAPACITY_BYTES, WorkerInfoField.WORKER_CAPACITY_BYTES_ON_TIERS,
        WorkerInfoField.LAST_CONTACT_SEC, WorkerInfoField.WORKER_USED_BYTES,
        WorkerInfoField.WORKER_USED_BYTES_ON_TIERS, WorkerInfoField.BUILD_VERSION,
        WorkerInfoField.ID, WorkerInfoField.STATE, WorkerInfoField.NUM_VCPU);
    workerOptions.setFieldRange(fieldRange);

    if (cl.hasOption(ReportCommand.LIVE_OPTION_NAME)) {
      workerOptions.setWorkerRange(WorkerRange.LIVE);
    } else if (cl.hasOption(ReportCommand.LOST_OPTION_NAME)) {
      workerOptions.setWorkerRange(WorkerRange.LOST);
    } else if (cl.hasOption(ReportCommand.DECOMMISSIONED_OPTION_NAME)) {
      workerOptions.setWorkerRange(WorkerRange.DECOMMISSIONED);
    } else if (cl.hasOption(ReportCommand.SPECIFIED_OPTION_NAME)) {
      workerOptions.setWorkerRange(WorkerRange.SPECIFIED);
      String addressString = cl.getOptionValue(ReportCommand.SPECIFIED_OPTION_NAME);
      String[] addressArray = addressString.split(",");
      // Addresses in GetWorkerReportOptions is only used when WorkerRange is SPECIFIED
      workerOptions.setAddresses(new HashSet<>(Arrays.asList(addressArray)));
    }
    return workerOptions;
  }

  /**
   * Gets the formatted tier values of a worker.
   *
   * @param map the map to get worker tier values from
   * @param workerName name of the worker
   * @return the formatted tier values of the input worker name
   */
  private static String getWorkerFormattedTierValues(Map<String, Map<String, String>> map,
      String workerName) {
    return map.values().stream().map((tierMap)
        -> (String.format("%-14s", tierMap.getOrDefault(workerName, "-"))))
        .collect(Collectors.joining(""));
  }

  /**
   * Initializes member variables used to collect worker info.
   */
  private void initVariables() {
    mSumCapacityBytes = 0;
    mSumUsedBytes = 0;
    mSumNumVCpus = 0;
    mSumCapacityBytesOnTierMap = new TreeMap<>(FileSystemAdminShellUtils::compareTierNames);
    mSumUsedBytesOnTierMap = new TreeMap<>(FileSystemAdminShellUtils::compareTierNames);

    // TierInfoMap is of form Map<Tier_Name, Map<Worker_Name, Worker_Tier_Value>>
    mCapacityTierInfoMap = new TreeMap<>(FileSystemAdminShellUtils::compareTierNames);
    mUsedTierInfoMap = new TreeMap<>(FileSystemAdminShellUtils::compareTierNames);
  }

  /**
   * Prints indented information.
   *
   * @param text information to print
   */
  private void print(String text) {
    String indent = Strings.repeat(" ", mIndentationLevel * INDENT_SIZE);
    mPrintStream.println(indent + text);
  }

  /**
   * @return report capacity command usage
   */
  public static String getUsage() {
    return "alluxio fsadmin report capacity [filter arg]\n"
        + "Report Alluxio capacity information.\n"
        + "Where [filter arg] is an optional argument. If no arguments passed in, "
        + "capacity information of all workers will be printed out.\n"
        + "[filter arg] can be one of the following:\n"
        + "    -live                   Live workers\n"
        + "    -lost                   Lost workers\n"
        + "    -workers <worker_names>  Specified workers, "
        + "host names or ip addresses separated by \",\"\n";
  }
}
