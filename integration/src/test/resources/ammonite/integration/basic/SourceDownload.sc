import $ivy.`com.lihaoyi::scalatags:0.6.2`

val loc = source.load(scalatags.Text)
val snip = loc.fileContent
  .lines
  .slice(loc.lineNum-15, loc.lineNum+15)
  .mkString("\n")

assert(snip.contains("object Text"))
assert(snip.contains("extends generic.Bundle"))