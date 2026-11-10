.class public LRepro;
.super Ljava/lang/Object;

# int count(List l): visits l[0..lastIndex] the way kotlinc compiles "for (i in 0..l.lastIndex)":
#   const/4 v1, 0        (i = 0)
#   if-gt v1, v2, :end   (0 > lastIndex -> nothing to visit)
# The comparison has the constant on the LEFT.
.method public static count(Ljava/util/List;)I
    .registers 5
    const/4 v0, 0                       # n = 0
    const/4 v1, 0                       # i = 0
    invoke-interface {p0}, Ljava/util/List;->size()I
    move-result v2
    add-int/lit8 v2, v2, -1             # lastIndex = size - 1
    if-gt v1, v2, :end                  # if (0 > lastIndex) goto end
    :loop
    invoke-interface {p0, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;
    add-int/lit8 v0, v0, 1
    if-eq v1, v2, :end
    add-int/lit8 v1, v1, 1
    goto :loop
    :end
    return v0
.end method

.method public static main([Ljava/lang/String;)V
    .registers 4
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;
    invoke-static {v0}, LRepro;->count(Ljava/util/List;)I
    move-result v2
    invoke-virtual {v1, v2}, Ljava/io/PrintStream;->println(I)V
    const-string v2, "x"
    invoke-interface {v0, v2}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    invoke-interface {v0, v2}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    invoke-static {v0}, LRepro;->count(Ljava/util/List;)I
    move-result v2
    invoke-virtual {v1, v2}, Ljava/io/PrintStream;->println(I)V
    return-void
.end method
