(function () {
    'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
    'ngRoute',
    'ngCookies',
    'ngAnimate',
    'ngMaterial',
    'slicebox.utils',
    'slicebox.directives',
    'slicebox.login',
    'slicebox.import',
    'slicebox.home',
    'slicebox.anonymization',
    'slicebox.transactions',
    'slicebox.log',
    'slicebox.adminPacs',
    'slicebox.adminForwarding',
    'slicebox.adminWatchDirectories',
    'slicebox.adminBoxes',
    'slicebox.adminUsers',
    'slicebox.adminSeriesTypes'
])

.config(function($locationProvider, $routeProvider, $mdThemingProvider, $animateProvider, $filterProvider) {
    $locationProvider.html5Mode(true);
    $routeProvider.otherwise({redirectTo: '/'});

    $mdThemingProvider.theme('default')
        .primaryPalette('blue-grey', {
            'default': '600',
        })
        .accentPalette('pink')
        .warnPalette('red');

    $mdThemingProvider.theme('redTheme')
        .primaryPalette('red');

    // Register filters
    $filterProvider.register('hexvalue', function() {
        return function(intValue, length) {
            var returnValue = intValue;

            if (!length) {
                length = 4;
            }

            if (angular.isDefined(intValue) && angular.isNumber(intValue) && intValue !== 0) {
                returnValue = intValue.toString(16);

                while (returnValue.length < length) {
                    returnValue = '0' + returnValue;
                }

                returnValue = returnValue.toUpperCase();
            }

            return returnValue;
        };
    });
})

.filter('prettyPatientName', function () {
    return function (text) {
        return text ? text.replace(new RegExp('\\^', 'g'), ' ') : '';
    };
})

.controller('SliceboxCtrl', function($scope, $http, $q, $location, $mdSidenav, $mdToast, $mdDialog, authenticationService, openConfirmActionModal) {

    $scope.uiState = {
        showMenu: true
    };

    $scope.toggleLeftNav = function() {
        $mdSidenav('leftNav').toggle();
    };

    $scope.logout = function() {
        authenticationService.clearCredentials();
        $scope.uiState.showMenu = false;
        $location.url("/login");
    };

    $scope.userSignedIn = function() {
        return authenticationService.userSignedIn();
    };

    $scope.isCurrentPath = function(path) { 
        return $location.path() === path;
    };

    $scope.currentPathStartsWith = function(path) { 
        return $location.path().indexOf(path) === 0;
    };
});

/*
.run(function ($rootScope, $location, $cookieStore, $http) {
    // keep user logged in after page refresh
    $rootScope.globals = $cookieStore.get('globals') || {};
    if ($rootScope.globals.currentUser) {
        $http.defaults.headers.common['Authorization'] = 'Basic ' + $rootScope.globals.currentUser.authdata; // jshint ignore:line
    }

    $rootScope.$on('$locationChangeStart', function (event, next, current) {
        // redirect to login page if not logged in
        if ($location.path() !== '/login' && !$rootScope.globals.currentUser) {
            $location.path('/login');
        }
    });
});
*/