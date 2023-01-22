package com.cmhteixeira.cmhtorrent

import com.cmhteixeira.bittorrent.client.CmhClient

import java.nio.file.Path
import org.jline.builtins.ConfigurationPath
import org.jline.console.SystemRegistry
import org.jline.console.impl.SystemRegistryImpl
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.DefaultParser.Bracket
import org.jline.reader.{EndOfFileException, LineReader, LineReaderBuilder, Parser, UserInterruptException}
import org.jline.terminal.{Size, Terminal, TerminalBuilder}
import org.jline.reader.LineReader.{Option => LineReaderOption}

class CmhTorrentREPL private (
    parser: Parser,
    systemRegistry: SystemRegistry,
    lineReader: LineReader
) {

  def run(): Unit = {
    def mainLoop(): Unit = {
      try {
        systemRegistry.cleanUp()
        val line = lineReader.readLine("cmhTorrent> ")
        val line2 =
          if (parser.getCommand(line).startsWith("!")) line.replaceFirst("!", "! ")
          else line
        systemRegistry.execute(line2)
        mainLoop()
      } catch {
        case _: UserInterruptException => mainLoop()
        case _: EndOfFileException => ()
        case error @ (_: Exception | _: Error) =>
          systemRegistry.trace(error)
          mainLoop()
      }
    }
    mainLoop()
    systemRegistry.close()
  }
}

object CmhTorrentREPL {
  case class ReplConfig(workingDir: Path, historyPath: Path)

  private def setUpParser: Parser = {
    val parser = new DefaultParser()
    parser.setEofOnUnclosedBracket(Bracket.CURLY, Bracket.ROUND, Bracket.SQUARE)
    parser.setEofOnUnclosedQuote(true)
    parser.setRegexCommand("[:]{0,1}[a-zA-Z!]{1,}\\S*") // change default regex to support shell commands

    parser.blockCommentDelims(new DefaultParser.BlockCommentDelims("/*", "*/")).lineCommentDelims(Array[String]("//"))
  }

  private def setUpTerminal: Terminal = {
    val terminal = TerminalBuilder.builder.build
    if ((terminal.getWidth == 0) || (terminal.getHeight == 0))
      terminal.setSize(new Size(120, 40)) // hard coded terminal size when redirecting

    terminal
  }

  private def setUpRegistry(terminal: Terminal, parser: Parser, workingDir: Path) = {
    val configPath: ConfigurationPath = new ConfigurationPath(workingDir, workingDir);

    val systemRegistry: SystemRegistryImpl =
      new SystemRegistryImpl(parser, terminal, () => workingDir, configPath)
    systemRegistry
  }

  private def setLineReader(terminal: Terminal, systemRegistry: SystemRegistry, parser: Parser, historyFile: Path) = {
    val reader: LineReader = LineReaderBuilder
      .builder()
      .terminal(terminal)
      .completer(systemRegistry.completer())
      .parser(parser)
      //      .highlighter(highlighter)
      .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%M%P > ")
      .variable(LineReader.INDENTATION, 2)
      .variable(LineReader.LIST_MAX, 100)
      .variable(LineReader.HISTORY_FILE, historyFile)
      .option(LineReaderOption.INSERT_BRACKET, true)
      .option(LineReaderOption.EMPTY_WORD_OPTIONS, false)
      .option(LineReaderOption.USE_FORWARD_SLASH, true) // use forward slash in directory separator
      .option(LineReaderOption.DISABLE_EVENT_EXPANSION, true)
      .build()

    reader
  }

  private def setMyCommands(
      client: CmhClient,
      systemRegistry: SystemRegistry,
      defaultDir: Path
  ): ReplCommandsInterface = {
    val myCommands = ReplCommandsInterface(client, defaultDir)
    systemRegistry.setCommandRegistries(myCommands)
    myCommands
  }

  def apply(client: CmhClient, config: ReplConfig): CmhTorrentREPL = {

    val parser = setUpParser
    val terminal = setUpTerminal
    val registry = setUpRegistry(terminal, parser, config.workingDir)
    val replInterface = setMyCommands(client, registry, config.workingDir)
    val lineReader = setLineReader(terminal, registry, parser, config.historyPath)
    replInterface.setLineReader(lineReader)
    new CmhTorrentREPL(parser, registry, lineReader)
  }
}
