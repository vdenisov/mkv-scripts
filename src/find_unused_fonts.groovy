String fontDir = "fonts"
String[] subtitleExtensions = new String[]{"ass"}

// Read all subtitle files from the current directory
var subtitleFiles = new File(".").listFiles().findAll { file -> subtitleExtensions.any { file.name.endsWith(it) } }
//println "Found ${subtitleFiles.size()} subtitle files"

List<String> subtitleLines = []

// Read each subtitle file line by line
subtitleFiles.each { file ->
    file.eachLine { line ->
        subtitleLines.add(line.toLowerCase())
    }
}

//println "Found a total of ${subtitleLines.size()} subtitle lines"

// Get all font file names
def fontFiles = new File(fontDir).listFiles().collect {
    it.name.lastIndexOf('.') >= 0 ? it.name.substring(0, it.name.lastIndexOf('.')) : it.name
}

//println "Found a total of ${fontFiles.size()} font files"

// Print font file names not present in subtitle lines
fontFiles.each { fontFile ->
    {
        def lowercaseFontFile = fontFile.toLowerCase()
        //println "Checking ${fontFile}"
        if (!subtitleLines.any { it.contains(lowercaseFontFile) }) {
            println fontFile
        }
    }
}