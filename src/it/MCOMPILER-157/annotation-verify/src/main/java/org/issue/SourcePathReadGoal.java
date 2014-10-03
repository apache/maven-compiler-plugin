package org.issue;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.io.FileUtils;

@Mojo( name = "read-source", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES )
public class SourcePathReadGoal
    extends AbstractMojo
{

    @Parameter
    protected String sourceClass;

    @Parameter
    protected String testSourceClass;

    @Parameter( defaultValue = "${project}" )
    protected MavenProject project;

    @SuppressWarnings( "unchecked" )
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( sourceClass != null )
        {
            getLog().info( "Checking compile source roots for: '" + sourceClass + "'" );
            assertGeneratedSourceFileFor( sourceClass, project.getCompileSourceRoots() );
        }

        if ( testSourceClass != null )
        {
            getLog().info( "Checking test-compile source roots for: '" + testSourceClass + "'" );
            assertGeneratedSourceFileFor( testSourceClass, project.getTestCompileSourceRoots() );
        }
    }

    private void assertGeneratedSourceFileFor( String sourceClass, List<String> sourceRoots )
        throws MojoFailureException, MojoExecutionException
    {
        String sourceFile = sourceClass.replace( '.', '/' )
                                       .concat( ".txt" );

        boolean found = false;
        for ( String root : sourceRoots )
        {
            File f = new File( root, sourceFile );
            getLog().info( "Looking for: " + f );
            if ( f.exists() )
            {
                try
                {
                    String[] nameParts = sourceClass.split( "\\." );
                    String content = FileUtils.fileRead( f );
                    if ( !nameParts[nameParts.length-1].equals( content ) )
                    {
                        throw new MojoFailureException( "Non-matching content in: " + f + "\n  expected: '"
                            + sourceClass + "'\n  found: '" + content + "'" );
                    }
                    
                    found = true;
                    break;
                }
                catch ( IOException e )
                {
                    throw new MojoExecutionException( "Cannot read contents of: " + f, e );
                }
            }
        }

        if ( !found )
        {
            throw new MojoFailureException( "Cannot find generated source file: " + sourceFile + " in:\n  "
                + StringUtils.join( sourceRoots.iterator(), "\n  " ) );
        }
    }

}
