package com.cmhteixeira.cmhtorrent

import com.cmhteixeira.bittorrent.client.CmhClient
import com.cmhteixeira.bittorrent.tracker.Tracker
import org.jline.builtins.Completers.FileNameCompleter
import org.jline.builtins.{Options, SyntaxHighlighter}
import org.jline.console.impl.{DefaultPrinter, JlineCommandRegistry}
import org.jline.console.{CommandInput, CommandMethods, CommandRegistry, Printer}
import org.jline.reader.{Completer, LineReader}
import org.jline.terminal.Terminal
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters.{collectionAsScalaIterable, mapAsJavaMap, seqAsJavaList}
import scala.util.{Failure, Success, Try}

class ReplCommandsInterface private (torrentClient: CmhClient, defaultDownloadDir: Path, printer: Printer)
    extends JlineCommandRegistry
    with CommandRegistry {
  private val logger = LoggerFactory.getLogger("RelpCommandsInterface")

  var reader: LineReader = null
  val syntaxHighlighter = SyntaxHighlighter.build("asd")

  registerCommands(mapAsJavaMap(commandsToExecute))

  private def commandsToExecute: Map[String, CommandMethods] = Map(
    "download" -> new CommandMethods(a => tDownloadExecute(a), a => tdownloadCompleter(a)),
    "list" -> new CommandMethods(a => listExecute(a), a => defaultCompleter(a)),
    "details" -> new CommandMethods(a => detailsExecute(a), a => defaultCompleter(a)),
    "peers" -> new CommandMethods(a => peersExecute(a), a => defaultCompleter(a)),
    "stats" -> new CommandMethods(a => stats(a), a => defaultCompleter(a))
    //      "tput" -> new CommandMethods(this::tput, this::tputCompleter),
    //    "testkey" -> new CommandMethods(this::testkey, this::defaultCompleter),
    //    "clear" -> new CommandMethods(this::clear, this::defaultCompleter),
    //    "echo" -> new CommandMethods(this::echo, this::defaultCompleter),
    //    "!" -> new CommandMethods(this::shell, this::defaultCompleter)
  )
  private def listExecute(input: CommandInput): Unit = {
    val usage: Array[String] =
      Array(
        "list -  lists all torrents",
        "Usage: list",
        "  -? --help                       Displays command help"
      )
    val opt: Iterable[String] = collectionAsScalaIterable(parseOptions(usage, input.xargs()).args())

    if (opt.isEmpty) {
      testprint
////      val res = torrentClient.listTorrents.mkString(", ")
//      val res = ""
//      syntaxHighlighter.highlight(new AttributedString(res, new AttributedStyle().background(7))).println(terminal())
    } else
      terminal().writer().println("Command takes no arguments.")
  }

  private def stats(input: CommandInput): Unit = {
    val usage: Array[String] =
      Array(
        "stats -  display statistics about internals of client",
        "Usage: stats",
        "  -? --help                       Displays command help",
        " --tracker                        Display statistics and state of the tracker."
      )
    val opt: Options = parseOptions(usage, input.xargs())
    Try(opt.isSet("tracker")) match {
      case Failure(exception) =>
        logger.error(s"When calling stats. Input was: ${input.args().mkString("Array(", ", ", ")")}", exception)
      case Success(true) => printTrackerStats(torrentClient.statistics)

      case Success(false) => terminal().writer().println(s"Not implemented")
    }
  }

  private def peersExecute(input: CommandInput): Unit =
    terminal().writer().print(s"Hi there. Peers command is: '$input'.")

  private def listCompleter(command: String): java.util.List[Completer] = ???

  private def detailsExecute(input: CommandInput): Unit = {
    val usage: Array[String] =
      Array(
        "details -  download status",
        "Usage: details [--index torrent-index]",
        "  -? --help                       Displays command help",
        " -i --index=torrent-index         Specifies with torrent to obtain details to."
      )
    val opt: Options = parseOptions(usage, input.xargs())
    val args = opt.args()
    val options = Try(opt.get("index"))
    terminal()
      .writer()
      .println(
        s"Details. Arguments are: [${collectionAsScalaIterable(args).mkString(", ")}]. Download dir: '$options'."
      )
  }

  private def testprint: Unit = {
    val torrentDetails = torrentClient.listTorrents
    val options = mapAsJavaMap(
      Map(
        Printer.COLUMNS -> seqAsJavaList(
          List("name", "piecesDownloaded", "peersConnected", "peersUnChoked", "peersReady")
        )
//        Printer.SHORT_NAMES -> true
      )
    ): java.util.Map[String, AnyRef]

    val data = seqAsJavaList(torrentDetails.toList.map {
      case (
            CmhClient.Torrent(_, name),
            CmhClient
              .TorrentDetails(piecesDownloaded, piecesTotal, peersTotal, peersConnected, peersUnchoked, peersReady)
          ) =>
        mapAsJavaMap(
          Map(
            "name" -> name,
            "piecesDownloaded" -> s"$piecesDownloaded/$piecesTotal",
            "peersConnected" -> s"$peersConnected/$peersTotal",
            "peersUnChoked" -> s"$peersUnchoked/$peersConnected",
            "peersReady" -> s"$peersReady/$peersConnected"
          )
        ): java.util.Map[String, AnyRef]
    })

    printer.println(options, data)

//    List<Map<String,Object>> data = new ArrayList<>();
//    data.add(fillMap("heikki", 10, "finland", "helsinki"));
//    data.add(fillMap("pietro", 11, "italy", "milano"));
//    data.add(fillMap("john", 12, "england", "london"));
//    printer.println(data);
//    Map<String,Object> options = new HashMap<>();
//    options.put(Printer.STRUCT_ON_TABLE, true);
//    options.put(Printer.VALUE_STYLE, "classpath:/org/jline/example/gron.nanorc");
//    printer.println(options,data);
//    options.clear();
//    options.put(Printer.COLUMNS, Arrays.asList("name", "age", "address.country", "address.town"));
//    options.put(Printer.SHORT_NAMES, true);
//    options.put(Printer.VALUE_STYLE, "classpath:/org/jline/example/gron.nanorc");
//    printer.println(options,data);
  }

  private def printTrackerStats(stats: Map[CmhClient.Torrent, Tracker.Statistics]): Unit = {
    val options = mapAsJavaMap(
      Map(
        Printer.COLUMNS -> seqAsJavaList(
          List(
            "name",
            "totalTrackers",
            "connectionsSent",
            "connectionsReceived",
            "announceSent",
            "announceReceived",
            "distinctPeers"
          )
        )
      )
    ): java.util.Map[String, AnyRef]

    val data = seqAsJavaList {
      stats.toList.map {
        case (
              CmhClient.Torrent(_, name),
              Tracker.Statistics(Tracker.Summary(totalTrackers, cSent, aSent, aReceived, peers), _)
            ) =>
          mapAsJavaMap(
            Map(
              "name" -> name,
              "totalTrackers" -> totalTrackers.toString,
              "connectionsSent" -> cSent.toString,
              "announceSent" -> aSent.toString,
              "announceReceived" -> aReceived.toString,
              "distinctPeers" -> peers.toString
            )
          )
      }
    }

    printer.println(options, data)
  }

  private def detailsCompleter(command: String): java.util.List[Completer] = ???

  private def tDownloadExecute(input: CommandInput): Unit = {
    val usage: Array[String] =
      Array(
        "download -  download a torrent",
        "Usage: download [-D download-dir] torrent-file",
        "  -? --help                       Displays command help",
        "  -D --dir=download-dir           Specifies the directory to download torrent to."
      )
    val opt: Options = parseOptions(usage, input.xargs())
    val args = collectionAsScalaIterable(opt.args()).toList
    val downloadDir = opt.get("dir") match {
      case "" => None
      case nonEmpty => Some(obtainPath(nonEmpty))
    }

    args match {
      case Nil => terminal().writer().println("You must provide the path to the torrent file.")
      case head :: Nil =>
        val torrentPath = obtainPath(head)
        val isDir = Files.isDirectory(torrentPath)
        if (isDir) {
          val allTorrentResults = torrentPath.toFile
            .listFiles((file, n) => n.endsWith(".torrent"))
            .toList
            .map(_.toPath)
            .map { torrentPath =>
              torrentPath -> torrentClient.downloadTorrent(
                torrentPath,
                downloadDir.getOrElse(defaultDownloadDir.resolve("pieces"))
              )
            }
          val failed = allTorrentResults.collect { case (path, Left(_)) => path }
          if (failed.nonEmpty)
            failed.foreach(path => terminal().writer().println(s"Error submitting torrent $path."))
        } else downloadSingleTorrent(torrentPath, downloadDir)

      case other => terminal().writer().println("You can only provide 1 torrent file at a time.")
    }
  }

  private def obtainPath(i: String): Path =
    i match {
      case s"~$rest" => Paths.get(System.getProperty("user.home"), rest)
      case a => Paths.get(a)
    }

  private def downloadSingleTorrent(torrentPath: Path, downloadDir: Option[Path]): Unit =
    torrentClient.downloadTorrent(
      torrentPath,
      downloadDir.getOrElse(defaultDownloadDir.resolve("pieces"))
    ) match {
      case Left(CmhClient.FileDoesNotExist) =>
        terminal().writer().println(s"The torrent file you provided ($torrentPath) does not exist.")
      case Left(CmhClient.ParsingError(error)) =>
        terminal().writer().println(s"There was an issue parsing the file you provided ($torrentPath): '$error'")
      case Right(_) => ()
    }

  private def tdownloadCompleter(command: String): java.util.List[Completer] =
    seqAsJavaList(List(new FileNameCompleter()))

  def setLineReader(theReader: LineReader): Unit =
    reader = theReader

  def terminal(): Terminal =
    reader.getTerminal

//  private void tput(CommandInput input) {
//    final String[] usage = {
//      "tput -  put terminal capability",
//      "Usage: tput [CAPABILITY]",
//      "  -? --help                       Displays command help"
//    };
//    try {
//      Options opt = parseOptions(usage, input.xargs());
//      List<String> argv = opt.args();
//      if (argv.size() > 0) {
//        Capability vcap = Capability.byName(argv.get(0));
//        if (vcap != null) {
//          terminal().puts(vcap, opt.argObjects().subList(1, argv.size()).toArray(new Object[0]));
//        } else {
//          terminal().writer().println("Unknown capability");
//        }
//      } else {
//        terminal().writer().println("Usage: tput [CAPABILITY]");
//      }
//    } catch (Exception e) {
//      saveException(e);
//    }
//  }
//
//  private void testkey(CommandInput input) {
//    final String[] usage = {
//      "testkey -  display the key events",
//      "Usage: testkey",
//      "  -? --help                       Displays command help"
//    };
//    try {
//      parseOptions(usage, input.args());
//      terminal().writer().write("Input the key event(Enter to complete): ");
//      terminal().writer().flush();
//      StringBuilder sb = new StringBuilder();
//      while (true) {
//        int c = ((LineReaderImpl) reader).readCharacter();
//        if (c == 10 || c == 13) break;
//        sb.append(new String(Character.toChars(c)));
//      }
//      terminal().writer().println(KeyMap.display(sb.toString()));
//      terminal().writer().flush();
//    } catch (Exception e) {
//      saveException(e);
//    }
//  }
//
//  private void clear(CommandInput input) {
//    final String[] usage = {
//      "clear -  clear terminal",
//      "Usage: clear",
//      "  -? --help                       Displays command help"
//    };
//    try {
//      parseOptions(usage, input.args());
//      terminal().puts(Capability.clear_screen);
//      terminal().flush();
//    } catch (Exception e) {
//      saveException(e);
//    }
//  }
//
//  private void echo(CommandInput input) {
//    final String[] usage = {
//      "echo - echos a value",
//      "Usage:  echo [-hV] <args>",
//      "-? --help                        Displays command help",
//      "-v --version                     Print version"
//    };
//    try {
//      Options opt = parseOptions(usage, input.args());
//      List<String> argv = opt.args();
//      if (opt.isSet("version")) {
//        terminal().writer().println("echo version: v0.1");
//      } else if (opt.args().size() >= 1) {
//        terminal().writer().println(String.join(" ", opt.args()));
//      }
//    } catch (Exception e) {
//      saveException(e);
//    }
//  }
//
//  private void executeCmnd(List<String> args) throws Exception {
//    ProcessBuilder builder = new ProcessBuilder();
//    List<String> _args = new ArrayList<>();
//    if (OSUtils.IS_WINDOWS) {
//      _args.add("cmd.exe");
//      _args.add("/c");
//    } else {
//      _args.add("sh");
//      _args.add("-c");
//    }
//    _args.add(String.join(" ", args));
//    builder.command(_args);
//    builder.directory(workDir.get().toFile());
//    Process process = builder.start();
//    StreamGobbler streamGobbler = new StreamGobbler(process.getInputStream(), System.out::println);
//    Thread th = new Thread(streamGobbler);
//    th.start();
//    int exitCode = process.waitFor();
//    th.join();
//    if (exitCode != 0) {
//      streamGobbler = new StreamGobbler(process.getErrorStream(), System.out::println);
//      th = new Thread(streamGobbler);
//      th.start();
//      th.join();
//      throw new Exception("Error occurred in shell!");
//    }
//  }
//
//  private void shell(CommandInput input) {
//    final String[] usage = { "!<command> -  execute shell command"
//      , "Usage: !<command>"
//      , "  -? --help                       Displays command help" };
//    if (input.args().length == 1 && (input.args()[0].equals("-?") || input.args()[0].equals("--help"))) {
//      try {
//        parseOptions(usage, input.args());
//      } catch (Exception e) {
//        saveException(e);
//      }
//    } else {
//      List<String> argv = new ArrayList<>(Arrays.asList(input.args()));
//      if (!argv.isEmpty()) {
//        try {
//          executeCmnd(argv);
//        } catch (Exception e) {
//          saveException(e);
//        }
//      }
//    }
//  }
//
//  private Set<String> capabilities() {
//    return InfoCmp.getCapabilitiesByName().keySet();
//  }
//
//  private List<Completer> tputCompleter(String command) {
//    List<Completer> completers = new ArrayList<>();
//    completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
//      , new OptionCompleter(new StringsCompleter(this::capabilities)
//        , this::commandOptions
//        , 1)
//    ));
//    return completers;
//  }

}

object ReplCommandsInterface {

  def apply(cmhClient: CmhClient, defaultDir: Path): ReplCommandsInterface =
    new ReplCommandsInterface(cmhClient, defaultDir, new DefaultPrinter(null)) // todo: Fix this
}
