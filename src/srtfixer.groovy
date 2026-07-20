import groovy.io.FileType

def currentDir = new File(".")

currentDir.eachFile FileType.FILES, { file ->
    {
        if (file.name.endsWith(".srt")) {
            println "Fixing ${file.name}"

            def fixed = new ArrayList<String>()
            def state = State.TIME

            def count = 1
            def skip = true;
            file.readLines().each { line -> {
                    if (line.startsWith("00:")) {
                        skip = false
                    }

                    if (!skip) {
                        switch (state) {
                            case State.TIME:
                                fixed.add(count++ as String)
                                fixed.add(parseTime(line))
                                state = State.TEXT
                                break
                            case State.TEXT:
                                fixed.addAll(parseText(line))
                                state = State.NEWLINE
                                break
                            case State.NEWLINE:
                                fixed.add("")
                                assert line.isEmpty()
                                state = State.TIME
                                break
                        }
                    }
                }
            }

            def output = new File(file.parent, file.name + ".fixed")
            output.withWriter { out ->
                fixed.each { line -> out.println(line) }
            }
        }
    }
}

enum State {
    TIME, TEXT, NEWLINE
}

static String parseTime(String line) {
    //00:01:41.42,00:01:42.30
    def matcher = line =~ "^(\\d\\d):(\\d\\d):(\\d\\d).(\\d\\d),(\\d\\d):(\\d\\d):(\\d\\d).(\\d\\d)\$"

    if (!matcher) {
        throw new RuntimeException("Invalid time format: ${line}")
    }

    return matcher.group(1) + ":" + matcher.group(2) + ":" + matcher.group(3) + "," + matcher.group(4) + "0" +
        " --> " +
        matcher.group(5) + ":" + matcher.group(6) + ":" + matcher.group(7) + "," + matcher.group(8) + "0"
}

static List<String> parseText(String line) {
    return line.split("\\[br]") as List<String>
}